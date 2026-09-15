package com.hmdp.aspect;

import com.hmdp.annotation.RateLimit;
import com.hmdp.config.RateLimitProperties;
import com.hmdp.exception.RateLimitException;
import com.hmdp.service.SlidingWindowRateLimiter;
import com.hmdp.utils.RateLimitKeyBuilder;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;
import java.lang.reflect.Method;

@Aspect
@Component
@Order(0)
@Slf4j
public class RateLimitAspect {
    private final RateLimitProperties properties;
    private final RateLimitKeyBuilder keys;
    private final SlidingWindowRateLimiter limiter;
    public RateLimitAspect(RateLimitProperties properties, RateLimitKeyBuilder keys, SlidingWindowRateLimiter limiter) {
        this.properties = properties; this.keys = keys; this.limiter = limiter;
    }

    @Around("@annotation(rule)")
    public Object check(ProceedingJoinPoint point, RateLimit rule) throws Throwable {
        if (!properties.isEnabled()) return point.proceed();
        if (rule.limit() <= 0 || rule.windowSeconds() <= 0) throw new IllegalArgumentException("限流注解配置不合法");
        Method method = AopUtils.getMostSpecificMethod(((MethodSignature) point.getSignature()).getMethod(),
                org.springframework.util.ClassUtils.getUserClass(point.getTarget()));
        ServletRequestAttributes context = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        String key = keys.build(method, rule.dimension(), context == null ? null : context.getRequest());
        long retry;
        try { retry = limiter.acquire(key, rule.limit(), rule.windowSeconds()); }
        catch (RuntimeException e) {
            // 限流基础设施异常时不放行到秒杀业务，返回 503，与触发阈值的 429 区分。
            log.warn("限流检查失败 api={} dimension={} cause={}", method.getName(), rule.dimension(), e.toString());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "限流服务暂不可用，请稍后重试");
        }
        if (retry > 0) throw new RateLimitException(retry);
        return point.proceed();
    }
}
