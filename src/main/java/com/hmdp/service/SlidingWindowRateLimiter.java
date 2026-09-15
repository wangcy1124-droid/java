package com.hmdp.service;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import java.util.Collections;
import java.util.UUID;

@Service
public class SlidingWindowRateLimiter {
    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>();
    static { SCRIPT.setLocation(new ClassPathResource("rate_limit.lua")); SCRIPT.setResultType(Long.class); }
    private final StringRedisTemplate redis;
    public SlidingWindowRateLimiter(StringRedisTemplate redis) { this.redis = redis; }

    /** 返回 0 为放行，正数为下次可以重试的毫秒数。 */
    public long acquire(String key, int limit, int windowSeconds) {
        if (limit <= 0 || windowSeconds <= 0) throw new IllegalArgumentException("限流阈值和窗口必须大于 0");
        Long retry = redis.execute(SCRIPT, Collections.singletonList(key), Integer.toString(limit),
                Long.toString(windowSeconds * 1000L), UUID.randomUUID().toString());
        if (retry == null) throw new IllegalStateException("Redis 未返回限流结果");
        return retry;
    }
}
