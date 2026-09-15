# 简历证据（截至 M6）

本项目基于黑马点评课程/开源项目二次开发。M0～M7 已实现并保留真实阶段验证；性能结论必须附带服务器环境、负载时长与入口/落库口径。

| 可陈述的技术点 | 代码入口 | 验证及实际结果 |
| --- | --- | --- |
| Redis Lua 原子库存、一人一单、时间窗口校验 | `VoucherOrderServiceImpl.seckillVoucher` → `OrderReservationService.reserve` → `seckill.lua` | `scripts/verify-m1.py`：40 用户抢 20 份成功 20 单；同用户 40 次成功 1 单；不是 TPS 压测 |
| 从 Stream 改为 RabbitMQ Direct Exchange 异步落库 | `OrderPublisher`、`OrderRabbitConfig`、`OrderListener` | `OrderMessagingIT`：真实 broker 发布/消费；最终源码无 Stream 通道 |
| 提交后 ACK、幂等、条件库存与最终唯一约束 | `OrderTransactionService`、`sql/01_order_delivery.sql`、`sql/02_order_lifecycle.sql` | 消息重投不重复扣库；故意遗漏 Redis 买家资格时唯一约束拒绝重复订单且库存更新回滚 |
| 发布失败、有限重试、DLQ 与补偿 | `OrderPublisher`、`OrderCompensationService`、`reservation_release.lua` | 实际 return/channel nack、3 次失败进 DLQ；已落库不补；旧 token 不释放新占用；补偿失败保留 parking 和恢复记录 |
| 超时未支付订单批量关闭、库存可重试释放 | `OrderCloseTask`、`OrderLifecycleService.close`、`OrderStockReleaseService`、`order_close_release.lua` | 真实定时扫描关单；24 个支付/关单请求只有一次状态迁移；20 次并发释放只加一次库存；Redis 故障、完成标记失败后可恢复 |
| 支付/关单 CAS 与关单后重新购买 | `OrderLifecycleService.simulatePay`、`OrderStatus`、`sql/02_order_lifecycle.sql` | 已支付不关闭、已关闭不支付，version 只增加一次；保留 CLOSED 历史的同时重新下单；迟到旧消息和旧释放请求不影响新占用 |
| Caffeine + Redis 商户二级缓存与热点保护 | `ShopCacheService`、`ShopCacheProperties` | `ShopCacheIT`：L1 命中不访问 Redis；L2 命中不查 DB；40 次并发冷查询或热点重建均仅回源一次；空值、随机 TTL、容量/过期生效 |
| 商户更新后缓存失效 | `ShopServiceImpl.update` → `ShopCacheService.invalidateAfterCommit` | 更新提交后 L1/L2 删除、回滚保留；通过阻塞真实旧查询验证不会在失效后回填旧数据；删除失败仍清 L1 |
| 通用 API/IP/USER 滑动窗口限流 | `annotation/RateLimit`、`aspect/RateLimitAspect`、`SlidingWindowRateLimiter`、`rate_limit.lua` | `RateLimitIT` 10 项通过；100 次并发调用阈值 20，实际放行 20；同用户跨 token/券 ID 共用限额；可信代理 IP 隔离；429 不执行业务 |
| ZSet 点赞与关系/计数幂等 | `SocialInteractionService`、`SocialTransactionService`、`SocialProjectionService`、`social_projection.lua` | 同用户 30 次并发点赞只加 1，30 次取消回到 0；多用户并发不丢计数；Top 5 时间顺序、Redis 丢失恢复、事务回滚通过 |
| Set 关注与共同关注 | `FollowServiceImpl`、`SocialMapper`、`sql/03_social.sql` | 唯一约束和重复请求幂等；并发关注/取关后双端一致；SINTER 后一次批量用户查询，Set 丢失可重建 |
| 消费与取消并发边界 | `OrderDeliveryMapper.lock`、`OrderTransactionService` | 8 轮并发，终态与双端库存一致；真实发布后模拟确认丢失覆盖两个提交顺序 |

验证命令：`mvn -q -Dtest=OrderLifecycleIT,OrderMessagingIT test`，在专用 MySQL/Redis/RabbitMQ 环境执行。M3 8 项 + M2 13 项组合回归全部通过；详见 [M3 记录](m3-verification.md) 和 [测试摘要](m3-test-results.txt)。M2 初次实现的历史记录见 [M2](m2-verification.md)。

面试可讲的取舍：confirm 超时不是未消费；数据库状态行协调取消与消费，Redis token 防旧补偿，Hash/ZSet 保存跨进程中断恢复线索。付出的是额外 Redis 写入和一张处理状态表，正常请求没有完整订单数据库事务。恢复任务可以取消超时未完成的异步请求，因此不把返回 orderId 等同于最终落库。

M2/M3 功能测试本身不提供性能结论；服务器500线程实测见本文 M7，不能将功能并发测试换算为TPS。

M3 取舍：支付仅为模拟接口，无第三方扣款。关单与 MySQL 回库、待释放记录在同一事务，Redis 在提交后通过幂等 Lua 最终完成；短暂跨存储不一致表现为暂时少卖。每笔关闭订单保留 Redis 去重标记，需要在将来的归档流程统一清理。唯一约束改为“每用户每券最多一张非 CLOSED 订单”，既保留历史，又支持关单后的资格释放。

M4 验证：`mvn -q -Dtest=ShopCacheIT test`，9 项通过；`python3 scripts/verify-m4.py` 验证打包应用商户 HTTP 链路。记录见 [M4](m4-verification.md)。取舍：逻辑过期允许短暂旧值；L1 失效协调仅覆盖单实例，若部署多实例须补广播；Redis 删除失败依靠物理 TTL 自愈，没有虚构消息补偿。没有用缓存并发功能测试宣称 TPS/P95。

M5 验证：`mvn -q -Dtest=RateLimitIT test`；`python3 scripts/verify-m5.py` 经真实 HTTP 和 RabbitMQ 完成下单/限流/恢复。见 [M5 记录](m5-verification.md)。取舍：Redis 时间统一窗口，方法签名隔离接口；仅可信代理头参与 IP 身份；拒绝请求不增加窗口计数，Redis 异常返回 503，不降级无限放行。ZSet 空间按活跃桶和阈值增长，成员随窗口 TTL 清理；功能并发验证不是性能压测。

M6 验证：`mvn -q -Dtest=SocialInteractionIT test`，12 项通过；`python3 scripts/verify-m6.py` 真实 HTTP 验证通过。见 [M6](m6-verification.md)。取舍：新增点赞身份表是为了计数事务和缓存可恢复；旧匿名历史总数保留、不伪造身份。推荐幂等状态接口，旧 toggle 仅兼容。Redis 是可重建的社交数据副本，允许事务提交与缓存同步之间短暂不一致，失败清缓存及固定快照 TTL 自愈；没有虚构跨存储原子提交或可靠消息推送。


## M7 部署与性能证据

实现入口：[部署](../deploy/README.md)、[JMeter](../jmeter/README.md)、[服务器记录](m7-verification.md)。包括Nginx、用户态实际部署、容器配置模板、独立测试用户/券、双重业务断言、异步订单/库存核对，以及真实JTL原始证据。

可用简历描述：

> 使用JMeter在服务器开展500线程秒杀入口压测，60秒场景中稳态约1000TPS、P95 18ms、错误率0%，并验证异步订单最终落库与库存一致性。

证据：2026-09-15，500线程、10秒爬升、60秒总时长、目标到达速率900请求/秒、数据10万行。全程59,425请求，受理984.656TPS、P9517ms、错误0；46秒稳态窗口实测999.587TPS、P9518ms、错误0，线程min=max=500，未耗尽数据。59,425笔逐笔落库核对成功，剩余库存两端均40,575；8项异步核对全通过。[正式summary](../jmeter/results/server-500-ssd/summary.json)、原始JTL（本地/服务器归档；公开仓库提供[汇总结果](../jmeter/results/server-500-ssd/summary.json)）。

必须解释的边界：JMeter与服务同一物理服务器，服务CPU affinity0–7、负载8–11，非独占；测试数据置于SSD；入口等待publisher confirm但不等待完整落库。最高采样队列积压30,018，结束后73.313秒完成核对。不能写“消费者持续1000TPS落库”“公网P95 18ms”或长时间稳定性已验证。

工程取舍与优化：服务器HDD对照10线程测试受理141.605TPS/P95202ms，MySQL实例采样平均commit19.86ms；将本项目运行数据迁到SSD、保持fsync/ACK/事务与池参数后，同10线程时长测试受理894.095TPS/P9513ms，订单均核对成功。再执行上述500线程正式测试。对照为短场景，不计算跨环境夸大的提升百分比。

早期WSL无速率限制测试失败（提前耗尽、队列积压）仍见 [本地历史](m7-local-verification.md)，未用后来的服务器成绩覆盖。Compose仅为模板；真实执行的是用户态部署。长期SSD持久目录和更长持续负载验证是后续工作，不包含在此次达标结论中。
