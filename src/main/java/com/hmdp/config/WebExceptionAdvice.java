package com.hmdp.config;

import com.hmdp.dto.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * @author CHEN
 * @date 2022/10/07
 */
@Slf4j
@RestControllerAdvice
public class WebExceptionAdvice {
    @ExceptionHandler(com.hmdp.exception.RateLimitException.class)
    public org.springframework.http.ResponseEntity<Result> handleRateLimit(com.hmdp.exception.RateLimitException e) {
        return org.springframework.http.ResponseEntity.status(429)
                .header("Retry-After", Long.toString(e.getRetryAfterSeconds()))
                .body(Result.fail(e.getMessage()));
    }

    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public org.springframework.http.ResponseEntity<Result> handleStatus(org.springframework.web.server.ResponseStatusException e) {
        return org.springframework.http.ResponseEntity.status(e.getStatus()).body(Result.fail(e.getReason()));
    }

    @ExceptionHandler(RuntimeException.class)
    public Result handleRuntimeException(RuntimeException e) {
        log.error(e.toString(), e);
        return Result.fail("服务器异常");
    }
}
