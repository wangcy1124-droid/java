package com.hmdp;

import com.hmdp.config.OrderRabbitConfig;
import com.hmdp.dto.OrderMessage;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;
import com.hmdp.entity.Voucher;
import com.hmdp.enums.SeckillResult;
import com.hmdp.listener.OrderListener;
import com.hmdp.service.*;
import com.hmdp.task.OrderReservationRecoveryTask;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.*;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static com.hmdp.config.OrderRabbitConfig.*;
import static com.hmdp.utils.RedisConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Explicitly run with -Dtest=OrderMessagingIT against dedicated MySQL/Redis/RabbitMQ. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"hmdp.order.recovery-age-ms=600000", "hmdp.order.recovery-delay-ms=3600000",
                "hmdp.order.close-enabled=false", "hmdp.rate-limit.enabled=false"})
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class OrderMessagingIT {
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate db;
    @Autowired StringRedisTemplate redis;
    @Autowired RabbitTemplate rabbit;
    @Autowired org.springframework.amqp.core.AmqpAdmin admin;
    @Autowired RabbitListenerEndpointRegistry registry;
    @Autowired IVoucherService vouchers;
    @Autowired IUserService users;
    @Autowired OrderReservationService reservations;
    @Autowired RedisIdWorker ids;
    @Autowired OrderListener listener;
    @Autowired OrderReservationRecoveryTask recovery;
    @SpyBean OrderPublisher publisher;
    @SpyBean OrderTransactionService transactions;
    @SpyBean OrderCompensationService compensation;
    private static final AtomicLong PHONE = new AtomicLong(System.currentTimeMillis() % 90000000);

    @AfterEach
    void restore() {
        reset(publisher, transactions, compensation);
        admin.declareExchange(new DirectExchange(EXCHANGE));
        admin.declareBinding(mainBinding());
        registry.getListenerContainer("orderCreate").start();
        registry.getListenerContainer("orderDeadLetter").start();
    }

    @Test
    void httpConcurrencyAndDatabaseUniqueness() throws Exception {
        long voucher = voucher(8);
        ExecutorService pool = Executors.newFixedThreadPool(12);
        try {
            List<Callable<Result>> work = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                String token = login(user());
                work.add(() -> buy(voucher, token));
            }
            int accepted = 0;
            for (Future<Result> f : pool.invokeAll(work)) {
                Result r = f.get();
                if (Boolean.TRUE.equals(r.getSuccess())) accepted++;
                else assertEquals("库存不足", r.getErrorMsg());
            }
            assertEquals(8, accepted);
            await(() -> count(voucher) == 8);
            assertEquals(0, stock(voucher));
            assertEquals("0", redis.opsForValue().get(SECKILL_STOCK_KEY + voucher));
            assertEquals(8L, db.queryForObject("SELECT COUNT(DISTINCT user_id) FROM tb_voucher_order WHERE voucher_id=?", Long.class, voucher));

            long single = voucher(8);
            String token = login(user());
            work.clear();
            for (int i = 0; i < 20; i++) work.add(() -> buy(single, token));
            accepted = 0;
            for (Future<Result> f : pool.invokeAll(work)) {
                Result r = f.get();
                if (Boolean.TRUE.equals(r.getSuccess())) accepted++;
                else assertEquals("禁止重复下单", r.getErrorMsg());
            }
            assertEquals(1, accepted);
            await(() -> count(single) == 1);
            assertEquals(7, stock(single));
            assertEquals("7", redis.opsForValue().get(SECKILL_STOCK_KEY + single));
        } finally { pool.shutdownNow(); }
    }

    @Test
    void duplicateMessagesAndDlqForCreatedOrderNeverReleaseStock() throws Exception {
        OrderMessage m = prepare(voucher(1), user());
        publisher.publish(m);
        await(() -> count(m.getVoucherId()) == 1 && !pending(m));
        publisher.publish(m);
        CountDownLatch handled = observeDlq(m);
        rabbit.convertAndSend(DLX, DLQ, m);
        assertTrue(handled.await(10, TimeUnit.SECONDS));
        assertEquals(1, count(m.getVoucherId()));
        assertEquals(0, stock(m.getVoucherId()));
        assertEquals("0", redis.opsForValue().get(SECKILL_STOCK_KEY + m.getVoucherId()));
    }

    @Test
    void exhaustedThreeAttemptsReachDlqAndCompensate() throws Exception {
        registry.getListenerContainer("orderDeadLetter").stop();
        OrderMessage m = prepare(voucher(1), user());
        db.update("UPDATE tb_seckill_voucher SET stock=0 WHERE voucher_id=?", m.getVoucherId());
        publisher.publish(m);
        await(() -> ready(DLQ) == 1);
        verify(transactions, times(3)).create(argThat(x -> x != null && x.getOrderId().equals(m.getOrderId())));
        assertEquals(0, count(m.getVoucherId()));
        registry.getListenerContainer("orderDeadLetter").start();
        await(() -> !pending(m));
        assertEquals("CANCELLED", state(m));
        assertEquals("1", redis.opsForValue().get(SECKILL_STOCK_KEY + m.getVoucherId()));
        assertFalse(Boolean.TRUE.equals(redis.opsForSet().isMember(SECKILL_ORDER_KEY + m.getVoucherId(), m.getUserId().toString())));
        // Fixture deliberately caused MySQL/Redis drift. Compensation restores only its Redis pre-deduction.
        assertEquals(0, stock(m.getVoucherId()));
    }

    @Test
    void unroutableReturnAndBrokerNackBothCancelReservation() {
        registry.getListenerContainer("orderCreate").stop();
        long a = voucher(1);
        long user = user();
        admin.removeBinding(mainBinding());
        Result returned = buy(a, login(user));
        assertFalse(returned.getSuccess());
        assertEquals("1", redis.opsForValue().get(SECKILL_STOCK_KEY + a));
        assertEquals(0, count(a));
        assertEquals(1L, db.queryForObject("SELECT COUNT(*) FROM tb_order_delivery WHERE voucher_id=? AND state='CANCELLED'", Long.class, a));
        admin.declareBinding(mainBinding());

        long b = voucher(1);
        admin.deleteExchange(EXCHANGE);
        Result nack = buy(b, login(user));
        assertFalse(nack.getSuccess());
        assertEquals("1", redis.opsForValue().get(SECKILL_STOCK_KEY + b));
        assertEquals(0, count(b));
    }

    @Test
    void ambiguousConfirmTimeoutFencesLateMessage() throws Exception {
        registry.getListenerContainer("orderCreate").stop();
        doAnswer(call -> { call.callRealMethod(); throw new TimeoutException("simulated lost confirm after real publish"); })
                .when(publisher).publish(any());
        long v = voucher(1);
        Result response = buy(v, login(user()));
        assertFalse(response.getSuccess());
        assertEquals("1", redis.opsForValue().get(SECKILL_STOCK_KEY + v));
        Message queued = rabbit.receive(QUEUE, 5000);
        assertNotNull(queued);
        OrderMessage m = (OrderMessage) rabbit.getMessageConverter().fromMessage(queued);
        assertEquals("CANCELLED", state(m));
        // Execute exactly the consumer path a late delivery would use.
        listener.create(m);
        assertEquals(0, count(v));
        assertEquals(1, stock(v));
        assertEquals("1", redis.opsForValue().get(SECKILL_STOCK_KEY + v));
    }

    @Test
    void ambiguousTimeoutAfterCommitStillReportsCreated() throws Exception {
        doAnswer(call -> {
            call.callRealMethod();
            OrderMessage message = call.getArgument(0);
            await(() -> count(message.getVoucherId()) == 1 && !pending(message));
            throw new TimeoutException("simulated confirm loss after DB commit");
        }).when(publisher).publish(any());
        long v = voucher(1);
        Result result = buy(v, login(user()));
        assertTrue(result.getSuccess());
        assertEquals(1, count(v));
        assertEquals(0, stock(v));
        assertEquals("0", redis.opsForValue().get(SECKILL_STOCK_KEY + v));
    }

    @Test
    void uniqueConstraintRollsBackStockAndCompensationKeepsExistingQualification() throws Exception {
        long v = voucher(2), u = user();
        OrderMessage first = prepare(v, u);
        publisher.publish(first);
        await(() -> !pending(first));
        // Deliberately stale cache: database must still enforce one order per user/voucher.
        redis.opsForSet().remove(SECKILL_ORDER_KEY + v, Long.toString(u));
        OrderMessage duplicate = prepare(v, u);
        publisher.publish(duplicate);
        await(() -> !pending(duplicate));
        assertEquals("CANCELLED", state(duplicate));
        assertEquals(1, count(v));
        assertEquals(1, stock(v));
        assertEquals("1", redis.opsForValue().get(SECKILL_STOCK_KEY + v));
        assertTrue(Boolean.TRUE.equals(redis.opsForSet().isMember(SECKILL_ORDER_KEY + v, Long.toString(u))));
        // 本测试主动破坏了资格缓存；恢复已核实的原订单 owner，供 M3 后续关单使用。
        redis.opsForHash().put(SECKILL_OWNER_KEY + v, Long.toString(u), first.getReservationId());
    }

    @Test
    void wrongReservationKeyTypeCannotCausePartialPreDeduction() {
        long v = voucher(1), u = user();
        redis.opsForValue().set(SECKILL_OWNER_KEY + v, "wrong-type");
        OrderMessage message = new OrderMessage();
        message.setUserId(u); message.setVoucherId(v);
        message.setReservationId(UUID.randomUUID().toString().replace("-", ""));
        assertEquals(SeckillResult.NOT_READY, reservations.reserve(message));
        assertEquals("1", redis.opsForValue().get(SECKILL_STOCK_KEY + v));
        assertFalse(pending(message));
        redis.delete(SECKILL_OWNER_KEY + v);
    }

    @Test
    void staleDlqCannotReleaseNewReservationForSameUser() throws Exception {
        long v = voucher(1), u = user();
        OrderMessage old = prepare(v, u);
        assertFalse(compensation.cancel(old, "TEST_CANCEL"));
        OrderMessage next = prepare(v, u);
        CountDownLatch handled = observeDlq(old);
        rabbit.convertAndSend(DLX, DLQ, old);
        assertTrue(handled.await(10, TimeUnit.SECONDS));
        assertEquals("0", redis.opsForValue().get(SECKILL_STOCK_KEY + v));
        assertTrue(reservations.isBound(next));
        publisher.publish(next);
        await(() -> !pending(next));
        assertEquals(1, count(v));
    }

    @Test
    void staleUnboundSnapshotCannotReleaseAnAlreadyCreatedOrder() {
        long v = voucher(1);
        OrderMessage m = unbound(v, user());
        OrderMessage snapshot = reservations.read(m.getReservationId());
        m.setOrderId(ids.nextId("order"));
        assertTrue(reservations.bind(m));
        listener.create(m);
        assertFalse(compensation.cancel(snapshot, "STALE_UNBOUND_SNAPSHOT"));
        assertEquals(1, count(v));
        assertEquals(0, stock(v));
        assertEquals("0", redis.opsForValue().get(SECKILL_STOCK_KEY + v));
    }

    @Test
    void failedRedisCompensationParksAndRecoveryRetries() throws Exception {
        OrderMessage m = prepare(voucher(1), user());
        db.update("UPDATE tb_seckill_voucher SET stock=0 WHERE voucher_id=?", m.getVoucherId());
        redis.delete(SECKILL_STOCK_KEY + m.getVoucherId());
        publisher.publish(m);
        Message parked = rabbit.receive(PARKING, 15000);
        assertNotNull(parked, "DLQ compensation failure must be retained in parking");
        OrderMessage parkedOrder = (OrderMessage) rabbit.getMessageConverter().fromMessage(parked);
        assertEquals(m.getOrderId(), parkedOrder.getOrderId(), "Parking must contain this test's order");
        assertNotNull(parked.getMessageProperties().getHeaders().get("x-death"));
        assertEquals("CANCELLED", state(m));
        assertTrue(pending(m));
        redis.opsForValue().set(SECKILL_STOCK_KEY + m.getVoucherId(), "0");
        age(m);
        recovery.recover();
        assertFalse(pending(m));
        assertEquals("1", redis.opsForValue().get(SECKILL_STOCK_KEY + m.getVoucherId()));
    }

    @Test
    void malformedPayloadIsIsolatedInsteadOfLoopingOrDisappearing() {
        rabbit.send(EXCHANGE, ROUTING, MessageBuilder.withBody("{broken".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON).build());
        Message parked = rabbit.receive(PARKING, 15000);
        assertNotNull(parked);
        assertEquals("{broken", new String(parked.getBody(), java.nio.charset.StandardCharsets.UTF_8));
        assertNotNull(parked.getMessageProperties().getHeaders().get("x-death"));
    }

    @Test
    void recoveryReclaimsCrashBeforePublishAndSerializesAgainstCreate() throws Exception {
        OrderMessage unbound = unbound(voucher(1), user());
        age(unbound);
        recovery.recover();
        assertFalse(pending(unbound));
        assertEquals("1", redis.opsForValue().get(SECKILL_STOCK_KEY + unbound.getVoucherId()));
        unbound.setOrderId(ids.nextId("order"));
        assertFalse(reservations.bind(unbound));
        OrderMessage bound = prepare(voucher(1), user());
        age(bound);
        recovery.recover();
        assertEquals("CANCELLED", state(bound));
        listener.create(bound);
        assertEquals(0, count(bound.getVoucherId()));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 8; i++) {
                OrderMessage m = prepare(voucher(1), user());
                CyclicBarrier start = new CyclicBarrier(2);
                Future<?> creating = pool.submit(() -> { barrier(start); listener.create(m); });
                Future<?> cancelling = pool.submit(() -> { barrier(start); compensation.cancel(m, "RACE"); });
                creating.get(15, TimeUnit.SECONDS);
                cancelling.get(15, TimeUnit.SECONDS);
                boolean created = "CREATED".equals(state(m));
                assertEquals(created ? 1 : 0, count(m.getVoucherId()));
                assertEquals(created ? 0 : 1, stock(m.getVoucherId()));
                assertEquals(created ? "0" : "1", redis.opsForValue().get(SECKILL_STOCK_KEY + m.getVoucherId()));
                assertFalse(pending(m));
            }
        } finally { pool.shutdownNow(); }
    }

    private CountDownLatch observeDlq(OrderMessage target) {
        CountDownLatch done = new CountDownLatch(1);
        doAnswer(call -> {
            Object result = call.callRealMethod();
            done.countDown();
            return result;
        }).when(compensation).cancel(argThat(m -> m != null && m.getOrderId().equals(target.getOrderId())), eq("DLQ"));
        return done;
    }

    private static void barrier(CyclicBarrier b) {
        try { b.await(10, TimeUnit.SECONDS); } catch (Exception e) { throw new RuntimeException(e); }
    }
    private void age(OrderMessage m) { redis.opsForZSet().add(SECKILL_PENDING_KEY, m.getReservationId(), 1); }
    private boolean pending(OrderMessage m) { return Boolean.TRUE.equals(redis.hasKey(SECKILL_RESERVATION_KEY + m.getReservationId())); }
    private String state(OrderMessage m) { return db.queryForObject("SELECT state FROM tb_order_delivery WHERE order_id=?", String.class, m.getOrderId()); }
    private int count(long v) { return db.queryForObject("SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=?", Integer.class, v); }
    private int stock(long v) { return db.queryForObject("SELECT stock FROM tb_seckill_voucher WHERE voucher_id=?", Integer.class, v); }
    private int ready(String queue) {
        Properties properties = admin.getQueueProperties(queue);
        return properties == null ? 0 : ((Number) properties.get(RabbitAdmin.QUEUE_MESSAGE_COUNT)).intValue();
    }
    private Binding mainBinding() { return new Binding(QUEUE, Binding.DestinationType.QUEUE, EXCHANGE, ROUTING, null); }
    private long voucher(int stock) {
        Voucher v = new Voucher();
        v.setShopId(1L); v.setTitle("M2 integration fixture"); v.setSubTitle("test"); v.setRules("test");
        v.setPayValue(100L); v.setActualValue(200L); v.setType(1); v.setStatus(1); v.setStock(stock);
        v.setBeginTime(LocalDateTime.now().minusMinutes(1)); v.setEndTime(LocalDateTime.now().plusHours(1));
        vouchers.addSeckillVoucher(v);
        return v.getId();
    }
    private long user() {
        User u = new User(); u.setPhone("137" + String.format("%08d", PHONE.incrementAndGet()));
        u.setNickName("M2 fixture"); users.save(u); return u.getId();
    }
    private String login(long user) {
        String token = UUID.randomUUID().toString();
        Map<String, String> fields = new HashMap<>(); fields.put("id", Long.toString(user)); fields.put("nickName", "M2");
        redis.opsForHash().putAll(LOGIN_USER_KEY + token, fields);
        redis.expire(LOGIN_USER_KEY + token, java.time.Duration.ofMinutes(10));
        return token;
    }
    private Result buy(long voucher, String token) {
        HttpHeaders headers = new HttpHeaders(); headers.set("authorization", token);
        return http.exchange("/voucher-order/seckill/" + voucher, HttpMethod.POST,
                new HttpEntity<>(headers), Result.class).getBody();
    }
    private OrderMessage unbound(long v, long u) {
        OrderMessage m = new OrderMessage(); m.setVoucherId(v); m.setUserId(u);
        m.setReservationId(UUID.randomUUID().toString().replace("-", ""));
        assertEquals(SeckillResult.SUCCESS, reservations.reserve(m));
        return m;
    }
    private OrderMessage prepare(long v, long u) {
        OrderMessage m = unbound(v, u); m.setOrderId(ids.nextId("order")); assertTrue(reservations.bind(m)); return m;
    }
    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
        }
        fail("Async condition was not met within 15 seconds");
    }
}
