package com.hmdp;

import com.hmdp.annotation.RateLimit;
import com.hmdp.config.RateLimitProperties;
import com.hmdp.dto.Result;
import com.hmdp.enums.RateLimitDimension;
import com.hmdp.service.SlidingWindowRateLimiter;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RateLimitKeyBuilder;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.hmdp.utils.RedisConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "hmdp.order.close-enabled=false", "hmdp.order.recovery-delay-ms=3600000", "hmdp.rate-limit.enabled=true"})
@Import(RateLimitIT.Endpoints.class)
@org.springframework.test.annotation.DirtiesContext
class RateLimitIT {
    @Autowired TestRestTemplate http;
    @Autowired StringRedisTemplate redis;
    @Autowired RateLimitKeyBuilder keys;
    @Autowired RateLimitProperties settings;
    @SpyBean SlidingWindowRateLimiter limiter;
    @SpyBean IVoucherOrderService orders;
    private static final AtomicInteger EXECUTED = new AtomicInteger();
    private final Set<String> privateKeys = new HashSet<>();

    @BeforeEach void prepare() {
        EXECUTED.set(0); settings.getTrustedProxies().clear();
        Set<String> buckets = redis.keys(RATE_LIMIT_KEY + "{com.hmdp.RateLimitIT*");
        if (buckets != null && !buckets.isEmpty()) redis.delete(buckets);
    }
    @AfterEach void restore() {
        reset(limiter, orders); settings.getTrustedProxies().clear();
        if (!privateKeys.isEmpty()) redis.delete(privateKeys);
    }

    @Test void apiQuotaSharedAcrossUsersAnd429HasRetryAfter() {
        assertEquals(200, call("api", login(91001), null).getStatusCodeValue());
        assertEquals(200, call("api", login(91002), null).getStatusCodeValue());
        ResponseEntity<Result> denied = call("api", login(91003), null);
        assertEquals(429, denied.getStatusCodeValue());
        assertEquals("请求过于频繁", denied.getBody().getErrorMsg());
        assertFalse(denied.getBody().getSuccess());
        assertTrue(Integer.parseInt(denied.getHeaders().getFirst("Retry-After")) >= 1);
        assertEquals(2, EXECUTED.get());
    }

    @Test void userQuotaIsolatedAndTokenRotationDoesNotBypassIt() {
        assertEquals(200, call("user", login(91004), null).getStatusCodeValue());
        assertEquals(200, call("user", login(91004), null).getStatusCodeValue());
        assertEquals(429, call("user", login(91004), null).getStatusCodeValue());
        assertEquals(200, call("user", login(91005), null).getStatusCodeValue());
        assertEquals(401, call("user", null, null).getStatusCodeValue());
        assertEquals(3, EXECUTED.get());
    }

    @Test void ipQuotaIgnoresUntrustedForwardedHeaders() {
        assertEquals(200, call("ip", null, "203.0.113.1").getStatusCodeValue());
        assertEquals(200, call("ip", null, "203.0.113.2").getStatusCodeValue());
        assertEquals(429, call("ip", null, "203.0.113.3").getStatusCodeValue());
        assertEquals(2, EXECUTED.get());
    }

    @Test void trustedProxyUsesRealIpAndRightmostUntrustedForwardedHop() {
        MockHttpServletRequest req = new MockHttpServletRequest(); req.setRemoteAddr("10.0.0.2");
        settings.getTrustedProxies().add("10.0.0.2");
        req.addHeader("X-Real-IP", "203.0.113.8");
        assertEquals("203.0.113.8", keys.clientIp(req));
        req.removeHeader("X-Real-IP");
        req.addHeader("X-Forwarded-For", "198.51.100.66, 203.0.113.9, 10.0.0.2");
        assertEquals("203.0.113.9", keys.clientIp(req));
        req.removeHeader("X-Forwarded-For"); req.addHeader("X-Real-IP", "not-an-ip");
        assertEquals("10.0.0.2", keys.clientIp(req));
        req.setRemoteAddr("::1"); req.addHeader("X-Forwarded-For", "203.0.113.99");
        assertEquals("0:0:0:0:0:0:0:1", keys.clientIp(req));
    }

    @Test void trustedProxySeparatesIpBuckets() {
        settings.getTrustedProxies().add("127.0.0.1");
        settings.getTrustedProxies().add("::1");
        assertEquals(200, call("ip", null, "203.0.113.10").getStatusCodeValue());
        assertEquals(200, call("ip", null, "203.0.113.10").getStatusCodeValue());
        assertEquals(429, call("ip", null, "203.0.113.10").getStatusCodeValue());
        assertEquals(200, call("ip", null, "203.0.113.11").getStatusCodeValue());
        assertEquals(3, EXECUTED.get());
    }

    @Test void windowRecoversWithoutRejectedRequestsExtendingTtl() throws Exception {
        String key = privateKey();
        assertEquals(0, limiter.acquire(key, 1, 1));
        long before = redis.getExpire(key, TimeUnit.MILLISECONDS);
        assertTrue(limiter.acquire(key, 1, 1) > 0);
        long after = redis.getExpire(key, TimeUnit.MILLISECONDS);
        assertTrue(after <= before);
        Thread.sleep(1100);
        assertEquals(0, limiter.acquire(key, 1, 1));
        assertEquals(1L, redis.opsForZSet().zCard(key));
    }

    @Test void partialWindowExpiresOldEntriesButKeepsRecentRequests() {
        String key = privateKey();
        assertEquals(0, limiter.acquire(key, 2, 10));
        double now = redis.opsForZSet().rangeWithScores(key, 0, 0).iterator().next().getScore();
        redis.opsForZSet().add(key, "older-than-window", now - 11000);
        assertEquals(0, limiter.acquire(key, 2, 10));
        assertNull(redis.opsForZSet().score(key, "older-than-window"));
        assertTrue(limiter.acquire(key, 2, 10) > 0);
        assertEquals(2L, redis.opsForZSet().zCard(key));
    }

    @Test void concurrentLuaCallsNeverExceedThreshold() throws Exception {
        String key = privateKey();
        ExecutorService pool = Executors.newFixedThreadPool(20);
        try {
            List<Callable<Long>> work = new ArrayList<>();
            for (int i = 0; i < 100; i++) work.add(() -> limiter.acquire(key, 20, 10));
            int accepted = 0;
            for (Future<Long> f : pool.invokeAll(work)) if (f.get() == 0) accepted++;
            assertEquals(20, accepted);
            assertEquals(20L, redis.opsForZSet().zCard(key));
        } finally { pool.shutdownNow(); }
    }

    @Test void seckillAnnotationBlocksBeforeBusinessAndSharesQuotaAcrossVoucherIds() {
        String token = login(92000 + System.currentTimeMillis() % 1000000);
        for (int i = 0; i < 5; i++) {
            ResponseEntity<Result> r = seckill(890000000L + i, token);
            assertEquals(200, r.getStatusCodeValue());
            assertFalse(r.getBody().getSuccess()); // missing vouchers still consume request quota
        }
        assertEquals(429, seckill(890000006L, token).getStatusCodeValue());
        verify(orders, times(5)).seckillVoucher(anyLong());
    }

    @Test void redisFailureReturns503AndNeverInvokesBusiness() {
        doThrow(new IllegalStateException("injected Redis unavailable")).when(limiter).acquire(anyString(), anyInt(), anyInt());
        assertEquals(503, call("api", null, null).getStatusCodeValue());
        assertEquals(0, EXECUTED.get());
    }

    private String privateKey() {
        String key = RATE_LIMIT_KEY + "it:" + UUID.randomUUID(); privateKeys.add(key); return key;
    }
    private String login(long id) {
        String token = UUID.randomUUID().toString();
        redis.opsForHash().put(LOGIN_USER_KEY + token, "id", Long.toString(id));
        redis.expire(LOGIN_USER_KEY + token, Duration.ofMinutes(5)); return token;
    }
    private ResponseEntity<Result> call(String name, String token, String ip) {
        HttpHeaders h = new HttpHeaders();
        if (token != null) h.set("authorization", token);
        if (ip != null) { h.set("X-Real-IP", ip); h.set("X-Forwarded-For", ip); }
        return http.exchange("/shop/m5-test/" + name, HttpMethod.GET, new HttpEntity<>(h), Result.class);
    }
    private ResponseEntity<Result> seckill(Long id, String token) {
        HttpHeaders h = new HttpHeaders(); h.set("authorization", token);
        return http.exchange("/voucher-order/seckill/" + id, HttpMethod.POST, new HttpEntity<>(h), Result.class);
    }

    /** 仅测试上下文注册，不进入生产 jar。 */
    @TestConfiguration
    @RestController
    @RequestMapping("/shop/m5-test")
    public static class Endpoints {
        @GetMapping("/api") @RateLimit(limit=2, windowSeconds=10, dimension=RateLimitDimension.API)
        public Result api() { EXECUTED.incrementAndGet(); return Result.ok(); }
        @GetMapping("/user") @RateLimit(limit=2, windowSeconds=10, dimension=RateLimitDimension.USER)
        public Result user() { EXECUTED.incrementAndGet(); return Result.ok(); }
        @GetMapping("/ip") @RateLimit(limit=2, windowSeconds=10, dimension=RateLimitDimension.IP)
        public Result ip() { EXECUTED.incrementAndGet(); return Result.ok(); }
    }
}
