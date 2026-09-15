package com.hmdp.service;

import com.hmdp.dto.OrderMessage;
import com.hmdp.enums.SeckillResult;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;

import static com.hmdp.utils.RedisConstants.*;

@Service
public class OrderReservationService {
    @Resource private StringRedisTemplate redis;
    private static final DefaultRedisScript<Long> RESERVE = script("seckill.lua");
    private static final DefaultRedisScript<Long> BIND = script("reservation_bind.lua");
    private static final DefaultRedisScript<Long> RELEASE = script("reservation_release.lua");
    private static final DefaultRedisScript<Long> COMPLETE = script("reservation_complete.lua");

    private static DefaultRedisScript<Long> script(String path) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(Long.class);
        return script;
    }

    public SeckillResult reserve(OrderMessage message) {
        Long id = message.getVoucherId();
        return SeckillResult.fromCode(redis.execute(RESERVE, Arrays.asList(SECKILL_STOCK_KEY + id,
                SECKILL_ORDER_KEY + id, SECKILL_META_KEY + id, SECKILL_OWNER_KEY + id,
                SECKILL_RESERVATION_KEY + message.getReservationId(), SECKILL_PENDING_KEY),
                message.getUserId().toString(), message.getReservationId(), id.toString()));
    }

    public boolean bind(OrderMessage message) {
        return Long.valueOf(1).equals(redis.execute(BIND, Arrays.asList(
                SECKILL_OWNER_KEY + message.getVoucherId(), SECKILL_RESERVATION_KEY + message.getReservationId()),
                message.getUserId().toString(), message.getReservationId(), message.getOrderId().toString()));
    }

    public boolean isBound(OrderMessage message) {
        return message.getReservationId().equals(redis.opsForHash().get(
                SECKILL_OWNER_KEY + message.getVoucherId(), message.getUserId().toString()))
                && message.getOrderId().toString().equals(redis.opsForHash().get(
                SECKILL_RESERVATION_KEY + message.getReservationId(), "orderId"));
    }

    public void release(OrderMessage message, boolean keepQualification) {
        Long result = redis.execute(RELEASE, Arrays.asList(SECKILL_STOCK_KEY + message.getVoucherId(),
                SECKILL_ORDER_KEY + message.getVoucherId(), SECKILL_OWNER_KEY + message.getVoucherId(),
                SECKILL_RESERVATION_KEY + message.getReservationId(), SECKILL_PENDING_KEY),
                message.getUserId().toString(), message.getReservationId(),
                message.getOrderId() == null ? "" : message.getOrderId().toString(), keepQualification ? "1" : "0", message.getVoucherId().toString());
        if (result == null || result < 0) throw new IllegalStateException("库存缺失，保留补偿记录等待核对");
    }

    public void complete(OrderMessage message) {
        redis.execute(COMPLETE, Arrays.asList(SECKILL_RESERVATION_KEY + message.getReservationId(),
                SECKILL_PENDING_KEY), message.getOrderId().toString(), message.getReservationId());
    }

    public Set<String> overdue(long ageMs) {
        return redis.opsForZSet().rangeByScore(SECKILL_PENDING_KEY, 0, System.currentTimeMillis() - ageMs, 0, 100);
    }

    public void defer(String token) {
        redis.execute(script("reservation_defer.lua"),
                java.util.Collections.singletonList(SECKILL_PENDING_KEY), token);
    }

    public OrderMessage read(String token) {
        Map<Object, Object> fields = redis.opsForHash().entries(SECKILL_RESERVATION_KEY + token);
        if (fields.isEmpty()) throw new IllegalStateException("占用记录缺失 token=" + token);
        OrderMessage message = new OrderMessage();
        message.setReservationId(token);
        message.setUserId(Long.valueOf(fields.get("userId").toString()));
        message.setVoucherId(Long.valueOf(fields.get("voucherId").toString()));
        if (fields.containsKey("orderId")) message.setOrderId(Long.valueOf(fields.get("orderId").toString()));
        return message;
    }
}
