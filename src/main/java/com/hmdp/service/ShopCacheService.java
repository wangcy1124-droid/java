package com.hmdp.service;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.hmdp.config.ShopCacheProperties;
import com.hmdp.dto.ShopCacheEntry;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import javax.annotation.PreDestroy;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import static com.hmdp.utils.RedisConstants.*;

/** 单应用实例 L1 一致性；Redis 重建锁也可协调多个实例回源。 */
@Service
@Slf4j
public class ShopCacheService {
    private final ShopMapper shops;
    private final StringRedisTemplate redis;
    private final RedissonClient redisson;
    private final ShopCacheProperties properties;
    private final Cache<Long, ShopCacheEntry> local;
    private final ReentrantReadWriteLock[] guards = new ReentrantReadWriteLock[256];
    private final Set<Long> rebuilding = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(64), r -> {
                Thread t = new Thread(r, "shop-cache-rebuild"); t.setDaemon(true); return t;
            }, new ThreadPoolExecutor.AbortPolicy());

    public ShopCacheService(ShopMapper shops, StringRedisTemplate redis, RedissonClient redisson,
                            ShopCacheProperties properties) {
        this.shops = shops; this.redis = redis; this.redisson = redisson; this.properties = properties;
        if (properties.getMaximumSize() <= 0 || properties.getL1Seconds() <= 0
                || properties.getTtlSeconds() <= properties.getNullSeconds() || properties.getNullSeconds() <= 0
                || properties.getJitterSeconds() < 0 || properties.getHotLogicalSeconds() <= 0
                || properties.getHotPhysicalSeconds() <= properties.getHotLogicalSeconds()
                || properties.getLockWaitMillis() < 0) throw new IllegalArgumentException("商户缓存配置不合法");
        local = Caffeine.newBuilder().maximumSize(properties.getMaximumSize())
                .expireAfterWrite(properties.getL1Seconds(), TimeUnit.SECONDS).recordStats().build();
        for (int i = 0; i < guards.length; i++) guards[i] = new ReentrantReadWriteLock(true);
    }

    private ReentrantReadWriteLock guard(Long id) { return guards[(id.hashCode() & Integer.MAX_VALUE) % guards.length]; }

    public Shop query(Long id) {
        ReentrantReadWriteLock.ReadLock read = guard(id).readLock();
        read.lock();
        try {
            ShopCacheEntry entry = local.getIfPresent(id);
            if (entry == null) {
                entry = readRedis(id);
                if (entry == null) entry = loadCold(id);
                if (entry.getData() != null) local.put(id, entry);
            }
            if (entry.getData() == null) return null;
            if (entry.getExpireTime() <= System.currentTimeMillis()) rebuildLater(id);
            // 返回副本，避免调用方修改共享 L1 对象。
            return BeanUtil.copyProperties(entry.getData(), Shop.class);
        } finally { read.unlock(); }
    }

    private ShopCacheEntry readRedis(Long id) {
        String json = redis.opsForValue().get(CACHE_SHOP_KEY + id);
        if (json == null) return null;
        if (json.isEmpty()) return new ShopCacheEntry(null, Long.MAX_VALUE);
        JSONObject object = JSONUtil.parseObj(json);
        if (object.containsKey("data") && object.containsKey("expireTime")) {
            return new ShopCacheEntry(object.getJSONObject("data").toBean(Shop.class), object.getLong("expireTime"));
        }
        // 兼容 M0～M3 的普通 Shop JSON；配置成热点后下次访问会异步迁移。
        return new ShopCacheEntry(object.toBean(Shop.class), properties.getHotIds().contains(id) ? 0 : Long.MAX_VALUE);
    }

    private ShopCacheEntry loadCold(Long id) {
        RLock lock = redisson.getLock(LOCK_SHOP_KEY + id);
        boolean acquired = false;
        try {
            // 不指定 leaseTime，使用 watchdog；在同一线程 finally 解锁。
            acquired = lock.tryLock(properties.getLockWaitMillis(), TimeUnit.MILLISECONDS);
            if (!acquired) throw new IllegalStateException("商户缓存正在重建，请稍后重试");
            ShopCacheEntry current = readRedis(id);
            return current != null ? current : loadDatabase(id);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("商户缓存重建等待被中断", e);
        } finally { if (acquired && lock.isHeldByCurrentThread()) lock.unlock(); }
    }

    private ShopCacheEntry loadDatabase(Long id) {
        Shop shop = shops.selectById(id);
        if (shop == null) {
            redis.opsForValue().set(CACHE_SHOP_KEY + id, "", properties.getNullSeconds(), TimeUnit.SECONDS);
            local.invalidate(id);
            return new ShopCacheEntry(null, Long.MAX_VALUE);
        }
        boolean hot = properties.getHotIds().contains(id);
        ShopCacheEntry entry = new ShopCacheEntry(shop, hot
                ? System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(properties.getHotLogicalSeconds()) : Long.MAX_VALUE);
        long ttl = (hot ? properties.getHotPhysicalSeconds() : properties.getTtlSeconds())
                + ThreadLocalRandom.current().nextLong(properties.getJitterSeconds() + 1);
        redis.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(hot ? entry : shop), ttl, TimeUnit.SECONDS);
        local.put(id, entry);
        return entry;
    }

    private void rebuildLater(Long id) {
        if (!rebuilding.add(id)) return;
        try {
            executor.execute(() -> {
                ReentrantReadWriteLock.ReadLock read = guard(id).readLock();
                read.lock();
                RLock lock = redisson.getLock(LOCK_SHOP_KEY + id);
                boolean acquired = false;
                try {
                    acquired = lock.tryLock();
                    if (!acquired) return;
                    ShopCacheEntry current = readRedis(id);
                    if (current == null || current.getExpireTime() <= System.currentTimeMillis()) loadDatabase(id);
                    else if (current.getData() != null) local.put(id, current);
                    else local.invalidate(id);
                } catch (RuntimeException e) {
                    log.error("热点商户重建失败，保留旧值 shopId={}", id, e);
                } finally {
                    try { if (acquired && lock.isHeldByCurrentThread()) lock.unlock(); }
                    finally { read.unlock(); rebuilding.remove(id); }
                }
            });
        } catch (RejectedExecutionException e) {
            rebuilding.remove(id);
            log.warn("热点商户重建队列已满，后续请求重试 shopId={}", id);
        }
    }

    /** 仅在更新事务提交成功后调用；等待在途回源和 L1 填充完成后再失效。 */
    public void invalidateAfterCommit(Long id) {
        ReentrantReadWriteLock.WriteLock write = guard(id).writeLock();
        write.lock();
        try {
            try { redis.delete(CACHE_SHOP_KEY + id); }
            catch (RuntimeException e) { log.error("商户更新已提交，Redis 删除失败，等待 TTL 自愈 shopId={}", id, e); }
            finally { local.invalidate(id); }
        } finally { write.unlock(); }
    }

    @PreDestroy
    public void shutdown() { executor.shutdownNow(); }
}
