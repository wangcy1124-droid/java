package com.hmdp.exception;

public class RateLimitException extends RuntimeException {
    private final long retryAfterSeconds;
    public RateLimitException(long retryMillis) {
        super("请求过于频繁");
        this.retryAfterSeconds = Math.max(1, (retryMillis + 999) / 1000);
    }
    public long getRetryAfterSeconds() { return retryAfterSeconds; }
}
