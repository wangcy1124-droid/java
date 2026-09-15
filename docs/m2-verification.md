# M2：RabbitMQ 异步下单与失败补偿

完成日期：2026-09-13，Asia/Shanghai。本文区分真实测试、模拟故障和未覆盖范围，不含性能结果。

## 改动入口

| 职责 | 实现 |
| --- | --- |
| 秒杀入口，先 Lua 成功再生成订单号 | `service/impl/VoucherOrderServiceImpl.java` |
| 原子资格、时间窗口、库存预扣和占用记录 | `resources/seckill.lua`、`service/OrderReservationService.java` |
| 队列、Direct Exchange、三次重试 | `config/OrderRabbitConfig.java` |
| 持久消息、mandatory、returns、confirm | `service/OrderPublisher.java` |
| 消费及 DLQ 监听 | `listener/OrderListener.java` |
| 订单+条件库存事务、消费/取消协调 | `service/OrderTransactionService.java`、`mapper/OrderDeliveryMapper.java` |
| 核实数据库后补偿 | `service/OrderCompensationService.java`、`resources/reservation_release.lua` |
| 进程退出/发布前中断/补偿失败恢复 | `task/OrderReservationRecoveryTask.java` |
| 最终一人一单索引与处理状态表 | `sql/01_order_delivery.sql` |
| 专项集成测试 | `src/test/java/com/hmdp/OrderMessagingIT.java` |

上述 Java 路径位于 `src/main/java/com/hmdp/`，资源位于 `src/main/`。已删除 `seckill_stream_bridge.lua`、Stream 消费线程、XGROUP 初始化和 XACK；运行代码不再读写 Stream。

## 消息链路与状态边界

- Direct Exchange：`seckill.order.exchange`，routing key：`seckill.order.create`。
- 主队列：`seckill.order.queue`，DLX：`seckill.order.dlx`，DLQ：`seckill.order.dlq`。
- 二次隔离队列：`seckill.order.parking`，没有自动消费者，保留坏消息及补偿连续失败的原消息。
- 主队列和 DLQ 每次投递均最多尝试 3 次，重试间隔 200/400ms。拒绝时不 requeue，分别进入 DLQ 或 parking。重启引起的 broker 重投会重新进入这套有限尝试流程，不宣称跨任意重启的全生命周期次数固定为三次。
- Spring AMQP AUTO 模式在监听方法正常返回后 ACK；事务在独立 Service 代理中提交，监听方法随后才清理 Redis 恢复记录并返回。Redis 清理异常会重试，同一订单的处理状态保证不会再扣数据库库存。

`tb_order_delivery` 不是用户订单生命周期表：

```text
OPEN -> CREATED    消费事务：库存条件扣减 + 插入订单 + 修改状态，统一提交
OPEN -> CANCELLED  失败处理事务：确认订单不存在后取消，提交后再释放 Redis
```

消费与取消都先创建/锁定同一 order_id 状态行，因此即使 publisher confirm 超时，补偿也不会与已提交订单冲突。若消费先完成，补偿只清理恢复记录；若取消先完成，迟到消息按已取消处理。

消息包含 `orderId/userId/voucherId/reservationId`。一人一单 Set 与 owner Hash 分工：Set 表示已占用资格，Hash 表示具体哪次占用。补偿必须匹配 token，不能释放后一次请求的资格。若数据库已经存在同用户同券的另一笔订单，补偿恢复本次 Redis 预扣库存但保留该用户资格，避免重复放行。

## 请求中断后的恢复

Lua 预扣时同时写 `seckill:reservation:{token}` Hash 和 `seckill:pending` ZSet。订单号在预扣成功后生成，再由 Lua 原子绑定：

- 在绑定前进程退出：恢复任务只能释放仍未绑定的占用；如果绑定先发生，旧快照不能释放。
- 绑定后、发布前退出：恢复任务先在数据库将订单标记 CANCELLED，再释放占用；以后同一消息不能创建订单。
- 消费提交后、Redis 清理前退出：任务查到订单已存在，只清理记录。
- Redis 补偿失败：数据库取消状态保留，Hash/ZSet 保留，下一次恢复继续执行；失败项延后，避免占满每批前 100 条。

专项测试还覆盖了更细的竞态：恢复任务读到未绑定快照后，实际订单已经绑定并完成，恢复脚本再次检查 Hash 已不存在，不能把已成功订单库存释放。

恢复默认每 10 秒扫描 100 条，超过 5 分钟尚未完成的有效占用会取消。这是异步下单完成期限，不是 M3 的未支付超时关闭；长时间排队的已确认消息也可能因此被取消。

## 环境与启动

上次会话因自动审批额度限制中断，随后 `/tmp` 工具和数据被清理。本次在 Git 忽略的 `.tools` 和 `.m2` 中重新建立了专用环境，未假设旧 PID 或旧测试数据仍存在：

| 组件 | 本次真实版本 / 监听 |
| --- | --- |
| Java / Maven | Temurin 8u504 / 3.9.9 |
| MySQL | 8.0.28，127.0.0.1:13306 |
| Redis | 6.2.16，127.0.0.1:16379，AOF 开启 |
| RabbitMQ / Erlang | 3.9.13 / 24.2.1 |
| AMQP / 管理页 | 127.0.0.1:15672 / 127.0.0.1:15673 |
| 应用 | Spring Boot 2.7.4，18081 |

新库依次执行 `00_base.sql`、`01_order_delivery.sql`，成功。可执行包最终构建：

```bash
source .tools/runtime/env.sh
mvn -q -o -DskipTests package
```

退出码 0，产物 `target/hmdp-1.0-SNAPSHOT.jar`。最终 jar 启动日志：

```text
2026-09-13 15:07:51.538 Tomcat started on port(s): 18081 (http)
2026-09-13 15:07:51.592 Created new connection ... amqp://guest@127.0.0.1:15672/
2026-09-13 15:07:51.665 Started HmDianPingApplication in 5.047 seconds (JVM running for 5.468)
```

当前会话应用保留运行：`http://localhost:18081/shop/1`，RabbitMQ 管理页 `http://localhost:15673`（本次回环开发实例 guest/guest）。此端口安排与 README 标准部署不同。当前未启动 Nginx，Compose 文件已补 RabbitMQ 和 SQL 挂载，但没有在 Docker 内运行。

临时环境文件在 `.tools/runtime/env.sh`；Java 日志 `app-m2.log`、中间件日志及数据均在 `.tools/runtime`。这些工具与数据不进入 Git。系统重启后服务需重新启动，永久部署按 README 标准步骤执行。

## 实际专项测试

在唯一测试应用连接专用数据库、Redis 和 vhost 的条件下执行：

```bash
source .tools/runtime/env.sh
mvn -q -o -Dtest=OrderMessagingIT test
```

最终 **13 tests / 0 failures / 0 errors / 0 skipped**，耗时 16.007 秒。逐项结果由 Surefire XML 提取并保存在 [m2-test-results.txt](m2-test-results.txt)。

| 场景 | 实际结果 |
| --- | --- |
| 20 用户争抢 8 份库存 | 成功 8 单，Redis/MySQL 库存 0，不同买家 8 人 |
| 同一用户 20 请求 | 成功 1 单，库存仅减 1 |
| 重投相同订单及给已落库订单投 DLQ | 订单仍为 1，库存未恢复 |
| MySQL 库存故意设为 0 | 消费方法调用恰好 3 次，进入 DLQ，最终取消并恢复 Redis 本次预扣 |
| 缓存故意遗漏已有买家资格 | 数据库唯一约束拒绝第二单，MySQL 扣减回滚，补偿保留原用户资格 |
| 实际取消 binding | 收到 mandatory return，入口失败并补偿 |
| 实际删除 Exchange | broker 关闭发布 channel/nack，入口失败并补偿 |
| 真实发布后模拟 confirm 丢失，消费尚未执行 | 数据库取消，迟到消费者不再创建订单 |
| 真实发布后模拟 confirm 丢失，消费已提交 | 入口确认订单已存在并返回订单号，不补库存 |
| 旧 DLQ + 同用户新一次占用 | 新 token 和新库存占用不受影响 |
| 丢失库存 key 导致 DLQ 补偿失败 | 原消息进入 parking；恢复库存 key 后任务补偿成功 |
| 非法 JSON | 消息进入 parking，保留 x-death，没有无限重入主队列 |
| 预扣后未绑定、已绑定但未发布 | 恢复任务分别安全释放或取消，迟到 bind/消息失效 |
| 8 轮消费/取消并发 | CREATED 或 CANCELLED 终态与订单、双端库存一致 |
| 恢复任务持有过期未绑定快照 | 已完成订单库存没有被释放 |
| Redis owner key 类型错误 | 拒绝资格校验，没有部分预扣 |

表格是 13 个测试方法内部多个断言分支的展开，不是另一批测试。确认超时由测试 spy 在真实 publish 后主动抛出 TimeoutException，**没有声称做了真实网络丢包注入**。异常库存案例故意制造 MySQL/Redis 差异，补偿只撤销本次 Redis 预扣，不伪造数据库剩余库存。

## 最终 jar 的业务回归

两个 Python 脚本已适配 RabbitMQ，在最终运行的 jar 上执行，均退出码 0。

`verify-m0.py`：登录/登出、签到、商户缓存、分类、博客点赞、关注/取关/共同关注、秒杀下单通过：

```text
EVIDENCE voucherId=53 orderId=637016243534037052 MySQL orders=2 stock=0 Redis stock=0 CREATED=2
```

`verify-m1.py`：时间边界、缺失状态、重复预热、资格并发通过：

```text
PASS 多用户并发: requests=40 accepted=20 stock=0 voucherId=56
PASS 同用户并发: requests=40 accepted=1 stock=19 voucherId=57
PASS RabbitMQ 已完成消费，待处理占用=0；本次不是性能压测
```

最终只读状态：

```text
EXISTS stream.orders = 0
ZCARD seckill:pending = 0
seckill.order.queue   ready=0 unacked=0 consumers=2
seckill.order.dlq     ready=0 unacked=0 consumers=2
seckill.order.parking ready=0 unacked=0 consumers=0
```

测试中的 parking 原消息已由测试读取并核对，线上 parking 不会被自动清空。

## 已知范围与下一步

- 使用经典持久队列、持久消息和发布确认，不是跨节点高可用 RabbitMQ 集群。DLX 存在 broker 故障转发丢失窗口，有效抢购的占用恢复记录用于后续核对。
- Redis 整体数据丢失、持久化丢失窗口和双存储同时故障仍需停流核对；没有宣称实现跨 MySQL/Redis/RabbitMQ 的分布式原子事务或 exactly-once。
- 历史券缺少库存 key 时不自动重建，避免重启覆盖未落库预扣。M1 → M2 需停流、排空旧 Stream 后切换；本次新建环境无法再次复用已清理的旧进程演示迁移。
- 下一个阶段 M3 才是 PENDING/PAID/CLOSED 订单状态、支付、超时关单及库存最多释放一次。本表 CANCELLED 只是消息投递取消，不是用户订单 CLOSED。
- 未执行 JMeter、服务器压测或 Docker Compose 实际启动。

实现语义参考：[Spring AMQP 2.4 参考](https://docs.spring.io/spring-amqp/docs/2.4.x/reference/html/)、[CorrelationData 的 return/confirm 顺序](https://docs.spring.io/spring-amqp/docs/2.4.17/api/org/springframework/amqp/rabbit/connection/CorrelationData.html)、[RabbitMQ DLX 故障边界](https://www.rabbitmq.com/docs/3.13/dlx)。
