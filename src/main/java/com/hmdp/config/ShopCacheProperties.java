package com.hmdp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.HashSet;
import java.util.Set;

@Data
@Component
@ConfigurationProperties(prefix = "hmdp.shop-cache")
public class ShopCacheProperties {
    private long maximumSize = 10000;
    private long l1Seconds = 5;
    private long ttlSeconds = 1800;
    private long jitterSeconds = 300;
    private long nullSeconds = 120;
    private long hotLogicalSeconds = 60;
    private long hotPhysicalSeconds = 300;
    private long lockWaitMillis = 2000;
    private Set<Long> hotIds = new HashSet<>();
}
