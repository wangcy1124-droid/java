# M5：通用 Redis 滑动窗口限流

实现及验证日期：2026-09-14，Asia/Shanghai。优先阅读本地《黑马点评.pdf》74～84 页滑动窗口章节，按 AGENTS.md 实现注解、AOP、ZSet/Lua 与三维度，不引入额外限流中间件。

## 关键修改

Java 文件位于 `src/main/java/com/hmdp/`。

| 文件 | 作用 |
| --- | --- |
| `annotation/RateLimit.java`、`enums/RateLimitDimension.java` | limit/windowSeconds/dimension 注解与 API/IP/USER 枚举 |
| `aspect/RateLimitAspect.java` | 方法执行前构造 key、调用限流，拒绝时不执行业务 |
| `utils/RateLimitKeyBuilder.java` | 接口签名 + 用户/IP/全局身份；可信代理头处理 |
| `service/SlidingWindowRateLimiter.java`、`src/main/resources/rate_limit.lua` | Redis 时间、ZSet、UUID 请求成员和原子滑动窗口 |
| `exception/RateLimitException.java`、`config/WebExceptionAdvice.java` | HTTP 429、Retry-After；401/503 清晰返回，429 不记录 ERROR |
| `config/RateLimitProperties.java`、`application.yml` | 默认开启、精确可信代理 IP 列表 |
| `controller/VoucherOrderController.java` | 秒杀每用户 10 秒 5 次，跨券 ID 共用桶 |
| `pom.xml` | Spring Boot 管理版本的 spring-boot-starter-aop |
| `frontend/js/common.js` | 显示 429/503 的服务端提示，不统一显示“服务器异常” |
| `src/test/java/com/hmdp/RateLimitIT.java` | 10 项专项集成测试，测试接口仅存在于测试上下文 |
| `scripts/verify-m5.py` | 打包应用的真实秒杀/429/窗口恢复验证 |

无 SQL 迁移。M2 消息链路测试增加自身 `hmdp.rate-limit.enabled=false` 配置，以保持原有高频请求的消息链路断言；生产配置默认开启。M1 的同用户高频脚本也须在关闭限流的专用实例运行，不能将符合预期的 429 视为秒杀库存实现失败。

## 设计边界

- key 格式为 `rate:{类名#方法名(参数类型)}:global/ip:地址/user:用户ID`，不包含变化的 URL 参数。
- API 桶在该方法所有调用方间共享；USER 桶使用 UserHolder 的用户 ID，换 token 不换桶；未登录的 USER 请求返回 401。
- IP 默认 remoteAddr。仅当直连地址在 `RATE_LIMIT_TRUSTED_PROXIES` 内时，才采用配置 Nginx 覆盖的 X-Real-IP；回退 X-Forwarded-For 时从右向左剥离可信代理。支持 IPv4/IPv6 字面量规范化，不接受域名、不做 DNS 查询。名单默认空，部署时填实际代理地址，不猜测 Docker 网段或信任整个网络。
- Lua 使用 Redis TIME，窗口为 `(now-window,now]`；ZREMRANGEBYSCORE、ZCARD、判断、ZADD、PEXPIRE 原子执行。member 含 UUID，同毫秒请求不覆盖。
- 放行的请求占一个名额，包括后来业务失败的请求。限流拒绝不加成员、不延长 TTL；Retry-After 根据最早成员剩余窗口向上取整为秒。
- 默认秒杀 USER 维度 5 次/10 秒，只限制个人请求，不对所有用户施加 5 次的总限额。
- 阈值拒绝返回 429，不执行库存 Lua 或发布消息，不记录 ERROR。限流检查异常返回 503 并记录 WARN；该方案不承诺 Redis 整体不可用时受保护接口继续服务。
- 注解适用于经过 Spring 代理调用的方法，不覆盖同类内部自调用。Redis 丢失窗口数据会重置额度；没有宣称灾难恢复下严格保留限额。

## 构建及专项测试

环境沿用本地 Java 8u504、Maven 3.9.9、MySQL 8.0.28（13306）、Redis 6.2.16（16379）、RabbitMQ 3.9.13（AMQP 15672）。测试连接真实 Redis、HTTP 和应用上下文；只在指定用例模拟限流服务故障。

```bash
source .tools/runtime/env.sh
mvn -q -DskipTests package
mvn -q -Dtest=RateLimitIT test
```

最终 **10 项，0 失败、0 错误、0 跳过，10.218 秒**：

1. API 全局桶在不同用户之间共享，超过阈值返回 429、错误消息和 Retry-After，业务执行次数不增加。
2. USER 之间独立；同用户不同 token 共享额度；未登录返回 401。
3. 不可信来源伪造不同转发头不能改变 IP 桶。
4. 可信代理读取 X-Real-IP、正确剥离 XFF、无有效头时回退，IPv6 规范化。
5. 可信代理后不同 IP 独立计数。
6. 拒绝请求不延长 TTL，1 秒窗口过去后可再次放行。
7. 移除已在窗口外的旧成员，保留仍在窗口内的近期请求，验证滚动窗口而非整桶重置。
8. 100 次调用、20 个工作线程、阈值 20：恰好放行 20 次，ZSet 恰好 20 个成员。
9. 实际秒杀 Controller 注解跨不同券 ID 共用用户额度，前 5 次进入业务，第 6 次被 AOP 拦截，业务 Spy 仅调用 5 次。
10. 注入限流 Redis 检查故障，HTTP 503，业务方法未执行。

此前首轮 9 项已通过，补齐独立 IP 桶用例后运行最终 10 项。日志仅有预期注入故障 WARN，没有限流异常 ERROR。并发测试不是 JMeter 性能结论。

前端执行 `node --check frontend/js/common.js`，并用 Node VM 调用 Axios 响应拦截器，确认 429/503 都返回服务端提示；未把该验证称为浏览器端到端测试。

## 打包应用结果

应用在 17:07:33 启动成功，耗时 4.84 秒，后端端口 18081。限流默认开启，订单支付有效期仍为默认 15 分钟。

```bash
source .tools/runtime/env.sh
java -jar target/hmdp-1.0-SNAPSHOT.jar
# 另一个终端加载同一环境
python3 scripts/verify-m5.py
```

HTTP 脚本真实登录两用户、创建库存 2 的秒杀券 156：

- 用户 A 首次成功下单，接下来 4 次为业务重复下单失败，第 6 次返回 HTTP 429，Retry-After=10；此时 MySQL/Redis 库存均为 1。
- 用户 B 独立放行并经 RabbitMQ 落库；最终订单 637418106444054657、637418106444054658，两端库存均为 0。
- 等待返回的重试时间后，用户 A 恢复进入业务，得到售罄的业务返回（HTTP 200），而非 429。

实际输出与测试方法列表见 [m5-test-results.txt](m5-test-results.txt)。应用保留运行供后续开发。没有全量审计、SHA 核验、无关 smoke test 或伪造性能结果。

下一步 M6：集中确认和补齐 Blog ZSet 点赞、Follow Set 关注、共同关注及批量查询的并发/一致性边界；M7 再进行部署和真实 JMeter 压测。
