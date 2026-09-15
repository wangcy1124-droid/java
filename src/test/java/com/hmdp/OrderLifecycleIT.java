package com.hmdp;

import com.hmdp.dto.*;
import com.hmdp.entity.*;
import com.hmdp.enums.SeckillResult;
import com.hmdp.mapper.*;
import com.hmdp.service.*;
import com.hmdp.task.OrderCloseTask;
import com.hmdp.utils.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static com.hmdp.utils.RedisConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real MySQL + Redis + RabbitMQ. Explicitly run against a dedicated development database. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"hmdp.order.close-enabled=false", "hmdp.order.recovery-age-ms=600000",
                "hmdp.order.recovery-delay-ms=3600000"})
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class OrderLifecycleIT {
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate db;
    @Autowired StringRedisTemplate redis;
    @Autowired IVoucherService vouchers;
    @Autowired IUserService users;
    @Autowired VoucherOrderMapper orders;
    @Autowired OrderReservationService reservations;
    @Autowired OrderPublisher publisher;
    @Autowired OrderTransactionService transactions;
    @Autowired OrderCompensationService compensation;
    @Autowired OrderLifecycleService lifecycle;
    @Autowired OrderStockReleaseService releaseService;
    @Autowired OrderCloseTask task;
    @Autowired RedisIdWorker ids;
    @SpyBean OrderStockReleaseMapper releases;
    private static final AtomicLong PHONE = new AtomicLong(System.currentTimeMillis() % 90000000);

    @AfterEach
    void resetState() {
        UserHolder.removeUser();
        reset(releases);
        ReflectionTestUtils.setField(task, "enabled", false);
    }

    @Test
    void rabbitCreatesPendingAndHttpPaymentChecksOwnership() {
        OrderMessage m = create(voucher(), user());
        VoucherOrder order = orders.selectById(m.getOrderId());
        assertEquals(1, order.getStatus());
        assertEquals(0, order.getVersion());
        assertEquals(Duration.ofSeconds(900), Duration.between(order.getCreateTime(), order.getExpireTime()));
        assertNull(order.getPayTime());
        String token = login(m.getUserId());
        String path = "/voucher-order/" + m.getOrderId();
        assertTrue(call(path, HttpMethod.GET, token).getSuccess());
        assertFalse(call(path, HttpMethod.GET, login(user())).getSuccess());
        assertEquals(HttpStatus.UNAUTHORIZED, http.getForEntity(path, Result.class).getStatusCode());
        assertFalse(call(path + "/simulate-pay", HttpMethod.POST, login(user())).getSuccess());
        assertTrue(call(path + "/simulate-pay", HttpMethod.POST, token).getSuccess());
        assertTrue(call(path + "/simulate-pay", HttpMethod.POST, token).getSuccess());
        assertEquals(2, orders.selectById(m.getOrderId()).getStatus());
        assertEquals(1, orders.selectById(m.getOrderId()).getVersion());
        assertNotNull(orders.selectById(m.getOrderId()).getPayTime());
        expire(m);
        assertFalse(lifecycle.close(m.getOrderId()));
        assertNull(releases.find(m.getOrderId()));
        assertStock(m, 0);
    }

    @Test
    void scanClosesExpiredButPreservesUnexpiredAndClosedCannotPay() {
        OrderMessage expired = create(voucher(), user());
        OrderMessage live = create(voucher(), user());
        assertFalse(lifecycle.close(live.getOrderId()));
        expire(expired);
        ReflectionTestUtils.setField(task, "enabled", true);
        ReflectionTestUtils.setField(task, "after", expired.getOrderId() - 1);
        task.scan();
        assertEquals(4, orders.selectById(expired.getOrderId()).getStatus());
        assertEquals(1, orders.selectById(expired.getOrderId()).getVersion());
        assertTrue(releases.find(expired.getOrderId()).getCompleted());
        assertEquals(1, orders.selectById(live.getOrderId()).getStatus());
        assertFalse(call("/voucher-order/" + expired.getOrderId() + "/simulate-pay", HttpMethod.POST,
                login(expired.getUserId())).getSuccess());
        assertStock(expired, 1);
        assertStock(live, 0);
    }

    @Test
    void expiredPaymentAndConcurrentCloseHaveOneTransitionAndOneRelease() throws Exception {
        OrderMessage m = create(voucher(), user());
        expire(m);
        ExecutorService pool = Executors.newFixedThreadPool(12);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Callable<Boolean>> work = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                work.add(() -> { start.await(); return lifecycle.close(m.getOrderId()); });
                work.add(() -> {
                    start.await(); asUser(m.getUserId());
                    try { return lifecycle.simulatePay(m.getOrderId()).getSuccess(); }
                    finally { UserHolder.removeUser(); }
                });
            }
            List<Future<Boolean>> results = new ArrayList<>();
            for (Callable<Boolean> w : work) results.add(pool.submit(w));
            start.countDown();
            int winners = 0;
            for (Future<Boolean> f : results) if (f.get(15, TimeUnit.SECONDS)) winners++;
            assertEquals(1, winners);
            OrderStockRelease snapshot = releases.find(m.getOrderId());
            List<Callable<Void>> retry = new ArrayList<>();
            for (int i = 0; i < 20; i++) retry.add(() -> { releaseService.release(snapshot); return null; });
            for (Future<Void> f : pool.invokeAll(retry)) f.get();
        } finally { pool.shutdownNow(); }
        assertEquals(4, orders.selectById(m.getOrderId()).getStatus());
        assertEquals(1, orders.selectById(m.getOrderId()).getVersion());
        assertNull(orders.selectById(m.getOrderId()).getPayTime());
        assertStock(m, 1);
        assertFalse(redis.opsForSet().isMember(SECKILL_ORDER_KEY + m.getVoucherId(), m.getUserId().toString()));
    }

    @Test
    void redisFailureLeavesDurableWorkAndDoesNotRepeatMysqlRelease() {
        OrderMessage m = create(voucher(), user());
        expire(m);
        redis.delete(SECKILL_STOCK_KEY + m.getVoucherId());
        assertTrue(lifecycle.close(m.getOrderId()));
        OrderStockRelease pending = releases.find(m.getOrderId());
        assertThrows(IllegalStateException.class, () -> releaseService.release(pending));
        assertFalse(releases.find(m.getOrderId()).getCompleted());
        assertFalse(lifecycle.close(m.getOrderId()));
        assertEquals(1, dbStock(m));
        // 恢复已核对的预扣后值，模拟 Redis 可用性恢复；不是自动重建丢失库存。
        redis.opsForValue().set(SECKILL_STOCK_KEY + m.getVoucherId(), "0");
        releaseService.retryPending();
        assertTrue(releases.find(m.getOrderId()).getCompleted());
        releaseService.release(pending);
        assertStock(m, 1);
    }

    @Test
    void lostCompletionAckAndOldMessagesCannotReleaseRepurchasedOrder() {
        OrderMessage old = create(voucher(), user());
        expire(old);
        assertTrue(lifecycle.close(old.getOrderId()));
        OrderStockRelease stale = releases.find(old.getOrderId());
        doThrow(new IllegalStateException("injected completion persistence failure"))
                .when(releases).complete(old.getOrderId());
        assertThrows(IllegalStateException.class, () -> releaseService.release(stale));
        assertStock(old, 1);
        assertFalse(releases.find(old.getOrderId()).getCompleted());
        reset(releases);
        OrderMessage next = create(old.getVoucherId(), old.getUserId());
        releaseService.release(stale);
        assertTrue(releases.find(old.getOrderId()).getCompleted());
        assertEquals(OrderTransactionService.Outcome.CREATED, transactions.create(old));
        assertTrue(compensation.cancel(old, "M3_LATE_DLQ"));
        assertStock(next, 0);
        assertEquals(next.getReservationId(), redis.opsForHash().get(SECKILL_OWNER_KEY + next.getVoucherId(),
                next.getUserId().toString()));
        assertEquals(2L, db.queryForObject("SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=? AND user_id=?",
                Long.class, old.getVoucherId(), old.getUserId()));
        expire(next);
        assertTrue(lifecycle.close(next.getOrderId()));
        releaseService.release(releases.find(next.getOrderId()));
        releaseService.release(stale);
        assertStock(next, 1);
    }

    @Test
    void releaseRecordFailureRollsBackClosedStateAndMysqlStock() {
        OrderMessage m = create(voucher(), user());
        expire(m);
        doThrow(new IllegalStateException("injected release insert failure")).when(releases).enqueue(m.getOrderId());
        assertThrows(IllegalStateException.class, () -> lifecycle.close(m.getOrderId()));
        assertEquals(1, orders.selectById(m.getOrderId()).getStatus());
        assertEquals(0, orders.selectById(m.getOrderId()).getVersion());
        assertNull(releases.find(m.getOrderId()));
        assertStock(m, 0);
        reset(releases);
        assertTrue(lifecycle.close(m.getOrderId()));
        releaseService.release(releases.find(m.getOrderId()));
        assertStock(m, 1);
    }

    @Test
    void reservationGenerationMismatchNeverReleasesAnotherOwnersStock() {
        OrderMessage m = create(voucher(), user());
        expire(m);
        assertTrue(lifecycle.close(m.getOrderId()));
        redis.opsForHash().put(SECKILL_OWNER_KEY + m.getVoucherId(), m.getUserId().toString(), "another-generation");
        assertThrows(IllegalStateException.class, () -> releaseService.release(releases.find(m.getOrderId())));
        assertEquals("0", redis.opsForValue().get(SECKILL_STOCK_KEY + m.getVoucherId()));
        assertTrue(redis.opsForSet().isMember(SECKILL_ORDER_KEY + m.getVoucherId(), m.getUserId().toString()));
        redis.opsForHash().put(SECKILL_OWNER_KEY + m.getVoucherId(), m.getUserId().toString(), m.getReservationId());
        releaseService.release(releases.find(m.getOrderId()));
        assertStock(m, 1);
    }

    @Test
    void legacyOrderWithoutDeliveryCanCloseOnce() {
        OrderMessage m = create(voucher(), user());
        db.update("DELETE FROM tb_order_delivery WHERE order_id=?", m.getOrderId());
        redis.opsForHash().delete(SECKILL_OWNER_KEY + m.getVoucherId(), m.getUserId().toString());
        expire(m);
        assertTrue(lifecycle.close(m.getOrderId()));
        OrderStockRelease pending = releases.find(m.getOrderId());
        assertNull(pending.getReservationId());
        releaseService.release(pending);
        releaseService.release(pending);
        assertStock(m, 1);
    }

    private void expire(OrderMessage m) {
        db.update("UPDATE tb_voucher_order SET expire_time=DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 1 SECOND) WHERE id=?",
                m.getOrderId());
    }
    private int dbStock(OrderMessage m) {
        return db.queryForObject("SELECT stock FROM tb_seckill_voucher WHERE voucher_id=?", Integer.class, m.getVoucherId());
    }
    private void assertStock(OrderMessage m, int expected) {
        assertEquals(expected, dbStock(m));
        assertEquals(Integer.toString(expected), redis.opsForValue().get(SECKILL_STOCK_KEY + m.getVoucherId()));
    }
    private OrderMessage create(long v, long u) {
        OrderMessage m = new OrderMessage(); m.setVoucherId(v); m.setUserId(u);
        m.setReservationId(UUID.randomUUID().toString().replace("-", ""));
        assertEquals(SeckillResult.SUCCESS, reservations.reserve(m));
        m.setOrderId(ids.nextId("order")); assertTrue(reservations.bind(m));
        try { publisher.publish(m); } catch (Exception e) { throw new AssertionError("Fixture publish failed", e); }
        await(() -> orders.selectById(m.getOrderId()) != null
                && !Boolean.TRUE.equals(redis.hasKey(SECKILL_RESERVATION_KEY + m.getReservationId())));
        return m;
    }
    private long voucher() {
        Voucher v = new Voucher(); v.setShopId(1L); v.setTitle("M3 fixture"); v.setSubTitle("lifecycle"); v.setRules("test");
        v.setPayValue(100L); v.setActualValue(200L); v.setType(1); v.setStatus(1); v.setStock(1);
        v.setBeginTime(java.time.LocalDateTime.now().minusMinutes(1));
        v.setEndTime(java.time.LocalDateTime.now().plusHours(1));
        vouchers.addSeckillVoucher(v); return v.getId();
    }
    private long user() {
        User u = new User(); u.setPhone("136" + String.format("%08d", PHONE.incrementAndGet()));
        u.setNickName("M3 fixture"); users.save(u); return u.getId();
    }
    private void asUser(long user) {
        UserDTO dto = new UserDTO(); dto.setId(user); UserHolder.saveUser(dto);
    }
    private String login(long user) {
        String token = UUID.randomUUID().toString();
        Map<String,String> fields = new HashMap<>(); fields.put("id", Long.toString(user)); fields.put("nickName", "M3");
        redis.opsForHash().putAll(LOGIN_USER_KEY + token, fields);
        redis.expire(LOGIN_USER_KEY + token, Duration.ofMinutes(10)); return token;
    }
    private Result call(String path, HttpMethod method, String token) {
        HttpHeaders headers = new HttpHeaders(); headers.set("authorization", token);
        return http.exchange(path, method, new HttpEntity<>(headers), Result.class).getBody();
    }
    private static void await(BooleanSupplier condition) {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < end) {
            if (condition.getAsBoolean()) return;
            try { Thread.sleep(25); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
        }
        fail("RabbitMQ order creation did not complete within 15 seconds");
    }
}
