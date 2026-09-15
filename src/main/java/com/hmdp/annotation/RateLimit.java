package com.hmdp.annotation;

import com.hmdp.enums.RateLimitDimension;
import java.lang.annotation.*;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimit {
    int limit();
    int windowSeconds();
    RateLimitDimension dimension() default RateLimitDimension.API;
}
