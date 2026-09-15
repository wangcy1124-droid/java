package com.hmdp.service;

import com.hmdp.entity.OrderStockRelease;
import com.hmdp.mapper.OrderStockReleaseMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import javax.annotation.Resource;
import java.util.Arrays;
import static com.hmdp.utils.RedisConstants.*;

@Service
@Slf4j
public class OrderStockReleaseService {
    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>();
    static {
        SCRIPT.setLocation(new ClassPathResource("order_close_release.lua"));
        SCRIPT.setResultType(Long.class);
    }
    @Resource private StringRedisTemplate redis;
    @Resource private OrderStockReleaseMapper releases;

    public void release(OrderStockRelease release) {
        if (Boolean.TRUE.equals(release.getCompleted())) return;
        Long result = redis.execute(SCRIPT, Arrays.asList(SECKILL_STOCK_KEY + release.getVoucherId(),
                SECKILL_ORDER_KEY + release.getVoucherId(), SECKILL_OWNER_KEY + release.getVoucherId(),
                SECKILL_CLOSED_KEY + release.getOrderId()), release.getUserId().toString(),
                release.getReservationId() == null ? "" : release.getReservationId());
        if (result == null || result < 0) throw new IllegalStateException("关单 Redis 占用状态不完整或代次不符");
        releases.complete(release.getOrderId());
        log.info("关单库存释放完成 orderId={} userId={} voucherId={} applied={}", release.getOrderId(),
                release.getUserId(), release.getVoucherId(), result == 1);
    }

    public void retryPending() {
        for (OrderStockRelease release : releases.pending()) {
            try {
                release(release);
            } catch (RuntimeException e) {
                log.error("关单 Redis 释放失败，将重试 orderId={} userId={} voucherId={}",
                        release.getOrderId(), release.getUserId(), release.getVoucherId(), e);
                releases.defer(release.getOrderId());
            }
        }
    }
}
