package com.hmdp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.HashSet;
import java.util.Set;

@Data
@Component
@ConfigurationProperties(prefix = "hmdp.rate-limit")
public class RateLimitProperties {
    private boolean enabled = true;
    /** 直接连接应用的可信代理 IP，精确匹配；空集合表示忽略代理请求头。 */
    private Set<String> trustedProxies = new HashSet<>();
}
