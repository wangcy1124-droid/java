package com.hmdp;

import cn.hutool.json.JSONUtil;
import com.github.benmanes.caffeine.cache.Cache;
import com.hmdp.config.ShopCacheProperties;
import com.hmdp.dto.ShopCacheEntry;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.service.ShopCacheService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.redisson.api.RedissonClient;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static com.hmdp.utils.RedisConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {"hmdp.order.close-enabled=false", "hmdp.order.recovery-delay-ms=3600000",
        "hmdp.shop-cache.maximum-size=3", "hmdp.shop-cache.l1-seconds=1", "hmdp.shop-cache.ttl-seconds=6",
        "hmdp.shop-cache.jitter-seconds=3", "hmdp.shop-cache.null-seconds=2",
        "hmdp.shop-cache.hot-logical-seconds=2", "hmdp.shop-cache.hot-physical-seconds=6"})
@org.springframework.test.annotation.DirtiesContext
class ShopCacheIT {
    @Autowired ShopCacheService cache;
    @Autowired IShopService service;
    @Autowired ShopCacheProperties settings;
    @Autowired JdbcTemplate db;
    @Autowired PlatformTransactionManager manager;
    @Autowired org.mybatis.spring.SqlSessionTemplate sqlSession;
    @Autowired RedissonClient redisson;
    @SpyBean ShopMapper mapper;
    @SpyBean StringRedisTemplate redis;

    @SuppressWarnings("unchecked")
    private Cache<Long, ShopCacheEntry> local() {
        return (Cache<Long, ShopCacheEntry>) ReflectionTestUtils.getField(cache, "local");
    }
    @AfterEach void restore() { reset(mapper, redis); local().invalidateAll(); }

    @Test void coldThenL1ThenRedisAndDefensiveCopy() {
        Shop s = fixture();
        assertEquals(s.getName(), cache.query(s.getId()).getName());
        long hits = local().stats().hitCount();
        // L1 should work without making any Redis calls.
        reset(redis);
        Shop copy = cache.query(s.getId()); copy.setName("caller mutation");
        assertEquals(s.getName(), cache.query(s.getId()).getName());
        verifyNoInteractions(redis);
        assertTrue(local().stats().hitCount() >= hits + 2);
        local().invalidate(s.getId());
        assertEquals(s.getName(), cache.query(s.getId()).getName());
        verify(mapper, times(1)).selectById(s.getId());
        long ttl = redis.getExpire(CACHE_SHOP_KEY + s.getId(), TimeUnit.MILLISECONDS);
        assertTrue(ttl > 5000 && ttl <= 9000, "random TTL within [6,9] seconds");
    }

    @Test void nullUsesShortRedisTtlAndAvoidsRepeatedDatabaseReads() {
        long id = 899999999L;
        redis.delete(CACHE_SHOP_KEY + id);
        assertNull(cache.query(id)); assertNull(cache.query(id));
        assertEquals("", redis.opsForValue().get(CACHE_SHOP_KEY + id));
        long ttl = redis.getExpire(CACHE_SHOP_KEY + id, TimeUnit.MILLISECONDS);
        assertTrue(ttl > 0 && ttl <= 2000);
        verify(mapper, times(1)).selectById(id);
        assertNull(local().getIfPresent(id));
    }

    @Test void l1ExpiresAndIsCapacityBounded() throws Exception {
        Shop s = fixture(); cache.query(s.getId());
        Thread.sleep(1100);
        assertNull(local().getIfPresent(s.getId()));
        assertNotNull(cache.query(s.getId()));
        verify(mapper, times(1)).selectById(s.getId());
        for (int i = 0; i < 6; i++) cache.query(fixture().getId());
        local().cleanUp();
        assertTrue(local().estimatedSize() <= 3);
    }

    @Test void concurrentColdMissOnlyLoadsDatabaseOnce() throws Exception {
        Shop s = fixture();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Callable<Shop>> tasks = new ArrayList<>();
            for (int i = 0; i < 40; i++) tasks.add(() -> cache.query(s.getId()));
            for (Future<Shop> f : pool.invokeAll(tasks)) assertEquals(s.getName(), f.get().getName());
            verify(mapper, times(1)).selectById(s.getId());
            assertFalse(redisson.getLock(LOCK_SHOP_KEY + s.getId()).isLocked());
        } finally { pool.shutdownNow(); }
    }

    @Test void expiredHotReturnsOldWithoutWaitingAndRebuildsOnce() throws Exception {
        Shop s = fixture(); settings.getHotIds().add(s.getId());
        expired(s);
        db.update("UPDATE tb_shop SET name='hot refreshed' WHERE id=?", s.getId());
        CountDownLatch entered = new CountDownLatch(1), resume = new CountDownLatch(1);
        doAnswer(call -> { entered.countDown(); assertTrue(resume.await(5, TimeUnit.SECONDS)); return actualShop(s.getId()); })
                .when(mapper).selectById(s.getId());
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            assertEquals(s.getName(), cache.query(s.getId()).getName());
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            List<Callable<Shop>> tasks = new ArrayList<>();
            for (int i = 0; i < 40; i++) tasks.add(() -> cache.query(s.getId()));
            // DB rebuild remains blocked while all readers finish with stale data.
            for (Future<Shop> f : pool.invokeAll(tasks)) assertEquals(s.getName(), f.get().getName());
            verify(mapper, times(1)).selectById(s.getId());
        } finally { resume.countDown(); pool.shutdownNow(); }
        await(() -> "hot refreshed".equals(cache.query(s.getId()).getName()));
        verify(mapper, times(1)).selectById(s.getId());
        assertTrue(redis.getExpire(CACHE_SHOP_KEY + s.getId()) > 0);
        await(() -> !redisson.getLock(LOCK_SHOP_KEY + s.getId()).isLocked());
    }

    @Test void hotDatabaseFailureKeepsOldValueAndAllowsNextRebuild() {
        Shop s = fixture(); settings.getHotIds().add(s.getId()); expired(s);
        doThrow(new IllegalStateException("injected cache database error")).when(mapper).selectById(s.getId());
        assertEquals(s.getName(), cache.query(s.getId()).getName());
        await(() -> !rebuilding().contains(s.getId()));
        assertEquals(s.getName(), JSONUtil.parseObj(redis.opsForValue().get(CACHE_SHOP_KEY + s.getId()))
                .getJSONObject("data").getStr("name"));
        assertFalse(redisson.getLock(LOCK_SHOP_KEY + s.getId()).isLocked());
        reset(mapper);
        db.update("UPDATE tb_shop SET name='retry refreshed' WHERE id=?", s.getId());
        cache.query(s.getId());
        await(() -> "retry refreshed".equals(cache.query(s.getId()).getName()));
        await(() -> !rebuilding().contains(s.getId()));
    }

    @Test void updateInvalidatesOnlyAfterCommitAndRollbackKeepsCache() {
        Shop s = fixture(); cache.query(s.getId());
        TransactionTemplate tx = new TransactionTemplate(manager);
        tx.execute(status -> {
            assertTrue(service.update(new Shop().setId(s.getId()).setName("rolled back")).getSuccess());
            assertNotNull(local().getIfPresent(s.getId()));
            assertTrue(redis.hasKey(CACHE_SHOP_KEY + s.getId()));
            status.setRollbackOnly(); return null;
        });
        assertEquals(s.getName(), cache.query(s.getId()).getName());
        assertEquals(s.getName(), db.queryForObject("SELECT name FROM tb_shop WHERE id=?", String.class, s.getId()));
        tx.execute(status -> {
            assertTrue(service.update(new Shop().setId(s.getId()).setName("committed")).getSuccess());
            assertNotNull(local().getIfPresent(s.getId()));
            return null;
        });
        assertNull(local().getIfPresent(s.getId()));
        assertFalse(redis.hasKey(CACHE_SHOP_KEY + s.getId()));
        assertEquals("committed", cache.query(s.getId()).getName());
        assertFalse(service.update(new Shop().setId(899999998L).setName("absent")).getSuccess());
    }

    @Test void updateCannotBeOverwrittenByInFlightOldRebuild() throws Exception {
        Shop s = fixture(); settings.getHotIds().add(s.getId()); expired(s);
        CountDownLatch oldRead = new CountDownLatch(1), resume = new CountDownLatch(1);
        doAnswer(call -> {
            Object old = actualShop(s.getId()); oldRead.countDown();
            assertTrue(resume.await(5, TimeUnit.SECONDS)); return old;
        }).when(mapper).selectById(s.getId());
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            cache.query(s.getId()); assertTrue(oldRead.await(3, TimeUnit.SECONDS));
            Future<?> updating = pool.submit(() -> service.update(new Shop().setId(s.getId()).setName("latest committed")));
            await(() -> "latest committed".equals(db.queryForObject("SELECT name FROM tb_shop WHERE id=?", String.class, s.getId())));
            // DB commit has happened; afterCommit waits for the old rebuild to finish then evicts it.
            assertFalse(updating.isDone());
            resume.countDown(); updating.get(5, TimeUnit.SECONDS);
            assertFalse(redis.hasKey(CACHE_SHOP_KEY + s.getId()));
            assertNull(local().getIfPresent(s.getId()));
            reset(mapper);
            assertEquals("latest committed", cache.query(s.getId()).getName());
        } finally { resume.countDown(); pool.shutdownNow(); }
        await(() -> !rebuilding().contains(s.getId()));
    }

    @Test void failedRedisDeleteStillInvalidatesL1AndDatabaseCommitSurvives() {
        Shop s = fixture(); cache.query(s.getId());
        doThrow(new IllegalStateException("injected Redis delete failure")).when(redis).delete(CACHE_SHOP_KEY + s.getId());
        assertTrue(service.update(new Shop().setId(s.getId()).setName("committed despite delete failure")).getSuccess());
        assertNull(local().getIfPresent(s.getId()));
        assertEquals("committed despite delete failure", db.queryForObject("SELECT name FROM tb_shop WHERE id=?", String.class, s.getId()));
        assertTrue(redis.getExpire(CACHE_SHOP_KEY + s.getId()) > 0);
        reset(redis);
        redis.delete(CACHE_SHOP_KEY + s.getId());
        assertEquals("committed despite delete failure", cache.query(s.getId()).getName());
    }

    private Shop fixture() {
        Shop s = new Shop().setName("M4 cache fixture").setTypeId(1L).setImages("/imgs/test.jpg")
                .setAddress("M4 test").setX(120.0).setY(30.0).setSold(0).setComments(0).setScore(50);
        assertEquals(1, mapper.insert(s)); return s;
    }
    private Shop actualShop(Long id) {
        // MyBatis Mapper 是接口代理；阻塞夹具通过真实 SqlSession 查询，不调用抽象方法的 callRealMethod。
        return sqlSession.selectOne("com.hmdp.mapper.ShopMapper.selectById", id);
    }
    private void expired(Shop s) {
        redis.opsForValue().set(CACHE_SHOP_KEY + s.getId(), JSONUtil.toJsonStr(new ShopCacheEntry(s, 1)), 9, TimeUnit.SECONDS);
        local().invalidate(s.getId());
    }
    @SuppressWarnings("unchecked") private Set<Long> rebuilding() {
        return (Set<Long>) ReflectionTestUtils.getField(cache, "rebuilding");
    }
    private static void await(BooleanSupplier condition) {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < end) {
            if (condition.getAsBoolean()) return;
            try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
        }
        fail("Cache condition not met within 5 seconds");
    }
}
