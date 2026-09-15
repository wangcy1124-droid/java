package com.hmdp.utils;

import com.hmdp.config.RateLimitProperties;
import com.hmdp.enums.RateLimitDimension;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import javax.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.stream.Collectors;

@Component
public class RateLimitKeyBuilder {
    private final RateLimitProperties properties;
    public RateLimitKeyBuilder(RateLimitProperties properties) { this.properties = properties; }

    public String build(Method method, RateLimitDimension dimension, HttpServletRequest request) {
        String api = method.getDeclaringClass().getName() + "#" + method.getName() + "("
                + Arrays.stream(method.getParameterTypes()).map(Class::getName).collect(Collectors.joining(",")) + ")";
        String identity;
        switch (dimension) {
            case USER:
                if (UserHolder.getUser() == null || UserHolder.getUser().getId() == null)
                    throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
                identity = "user:" + UserHolder.getUser().getId(); break;
            case IP:
                if (request == null) throw new IllegalStateException("IP 限流需要 HTTP 请求上下文");
                identity = "ip:" + clientIp(request); break;
            default: identity = "global";
        }
        // Java 方法签名稳定标识接口，不把路径参数加入 key，切换券 ID 不能绕过限流。
        return RedisConstants.RATE_LIMIT_KEY + "{" + api + "}:" + identity;
    }

    public String clientIp(HttpServletRequest request) {
        String peer = normalize(request.getRemoteAddr());
        if (peer == null) throw new IllegalArgumentException("请求来源 IP 不合法");
        if (!trusted(peer)) return peer;
        // 配套 Nginx 会覆盖 X-Real-IP；只有直接来源是已配置代理时才读取。
        String real = normalize(request.getHeader("X-Real-IP"));
        if (real != null) return real;
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null) {
            String[] hops = forwarded.split(",", -1);
            // 从最近一跳向前剥离可信代理，不能直接相信客户端追加的最左一项。
            for (int i = hops.length - 1; i >= 0; i--) {
                String ip = normalize(hops[i]);
                if (ip == null) return peer;
                if (!trusted(ip)) return ip;
            }
        }
        return peer;
    }

    private boolean trusted(String ip) {
        return properties.getTrustedProxies().stream().map(this::normalize).anyMatch(ip::equals);
    }

    private String normalize(String value) {
        if (value == null) return null;
        String ip = value.trim();
        if (ip.contains(":")) {
            if (!ip.matches("[0-9a-fA-F:.]+")) return null;
        } else {
            if (!ip.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) return null;
            for (String part : ip.split("\\.")) if (Integer.parseInt(part) > 255) return null;
        }
        // 仅对 IP 字面量规范化，不接受域名、不触发 DNS 查询。
        try { return InetAddress.getByName(ip).getHostAddress(); }
        catch (java.net.UnknownHostException e) { return null; }
    }
}
