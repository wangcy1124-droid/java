# M3：订单生命周期与超时库存释放

验证日期：2026-09-14，Asia/Shanghai。已优先阅读本地《黑马点评.pdf》66、70～73 页订单超时及支付/关单竞争内容；采用项目要求的 Spring Task、MyBatis 状态/版本 CAS 和 Redis Lua，未引入 PDF 示例中的 Kafka/JPA。

## 实际修改

| 职责 | 关键文件（Java 均在 src/main/java/com/hmdp 下） |
| --- | --- |
| 状态、版本与支付截止时间 | `enums/OrderStatus.java`、`entity/VoucherOrder.java` |
| 消费落库时默认待支付、900 秒有效期 | `service/OrderTransactionService.java` |
| 本人订单查询、模拟支付、关单 CAS | `controller/VoucherOrderController.java`、`service/OrderLifecycleService.java` |
| 每轮最多 100 条的关单扫描 | `task/OrderCloseTask.java` |
| MySQL 持久化待释放记录 | `entity/OrderStockRelease.java`、`mapper/OrderStockReleaseMapper.java` |
| Redis 释放及重试 | `service/OrderStockReleaseService.java`、`src/main/resources/order_close_release.lua` |
| 关闭历史、有效订单唯一约束、扫描索引 | `sql/02_order_lifecycle.sql` |
| 配置、首次初始化与运行说明 | `application.yml`、`docker-compose.yml`、`README.md` |
| 专项验证 | `src/test/java/com/hmdp/OrderLifecycleIT.java`、`scripts/verify-m3.py` |

M2 的消费失败核对和库存预热查询均排除 CLOSED 历史订单。仍保留 `tb_order_delivery` 对旧 RabbitMQ 消息的处理状态，关闭后重投旧消息不会重新扣库。

## 状态和跨存储边界

- 状态沿用课程数字：PENDING=1、PAID=2、CLOSED=4；3/5/6 不新增流转。
- 创建时间和截止时间来自数据库时钟，`expire_time=create_time+配置秒数`，version=0。
- 支付更新条件为 `id + status=1 + version + expire_time > CURRENT_TIMESTAMP`；重复支付已 PAID 订单幂等成功。只允许当前登录用户查询/支付自己的订单。
- 关单条件为 `id + status=1 + version + expire_time <= CURRENT_TIMESTAMP`。状态、version+1、MySQL stock+1、待释放记录同时提交，任一步出错整体回滚。
- 提交后执行 Redis Lua。库存、用户资格、owner、已释放标记原子修改。失败留在 MySQL，扫描每批最多 100 条、失败至少 10 秒后重试；数据库完成标记失败也不会重复加 Redis 库存。
- 券上的 owner 必须匹配本订单 token；M0/M1 无投递记录的历史订单仅在 owner 为空且仍有用户资格时释放。状态缺失、错误类型、不同代次均保留任务并记录上下文，不猜测库存。
- Redis 去重标记不设置 TTL，覆盖旧重试晚于新下单的情形。未来归档时需在核实释放完成、停止旧任务后清理；不覆盖 Redis 全量丢失、MySQL 丢失等灾难恢复场景。

原 `(user_id,voucher_id)` 永久唯一会阻止关单后重新购买。迁移增加生成列 active_user_id：status=4 时 NULL，否则等于 user_id，建立 `(active_user_id,voucher_id)` 唯一索引并保留关闭历史。数据库仍拒绝同用户同券多张有效订单。

## 构建和集成结果

使用仓库忽略目录中的 Java 8u504、Maven 3.9.9，实际依赖 MySQL 8.0.28（13306）、Redis 6.2.16（16379）、RabbitMQ 3.9.13（AMQP 15672）。沿用已有数据目录，应用迁移 02，没有清库。

```bash
source .tools/runtime/env.sh
mvn -q -DskipTests package
mvn -q -Dtest=OrderLifecycleIT,OrderMessagingIT test
```

最终组合验证：**21 项，0 失败、0 错误、0 跳过**。M3 8 项用真实数据库、Redis 和 RabbitMQ；故障点只在指定 Mapper 方法注入异常，或主动破坏夹具 Redis 数据。

1. RabbitMQ 落库 PENDING、version=0、900 秒有效期；本人 HTTP 查询/支付成功，非本人拒绝，未登录 401。
2. 同订单重复支付 version 仍为 1；PAID 超过截止时间后不被关闭。
3. 扫描关闭超时订单、保留未超时订单，CLOSED 再支付失败。
4. 12 次支付与 12 次关单同时请求已到期订单，仅一次状态迁移；20 次并发 Redis 释放仅回库一次。支付先成功的顺序由第 2 项另行验证，不把这个已到期竞争描述成随机各胜一半。
5. 关单时 Redis 库存键缺失，MySQL 只恢复一次且保留待释放记录；恢复已核对的 Redis 值后重试成功。
6. Lua 成功但数据库完成标记写入失败；用户重新购买后重试旧释放和旧消息，均不动新库存/资格；新订单也能独立关闭。
7. 待释放记录插入故障使关单和 MySQL 回库一起回滚；错误 owner 不能被释放。
8. 无 M2 投递记录的历史订单可以关闭并且只释放一次。

测试分布具体以 [m3-test-results.txt](m3-test-results.txt) 的方法名为准。M2 13 项覆盖 confirm、return/nack、事务、有限重试、DLQ、隔离、补偿与幂等回归。

过程曾遇本地 MySQL 停止导致启动失败，恢复后重跑；首次组合有一次 DLQ 等待断言失败，单独复现通过，增加 parking 消息订单 ID 断言，最终组合通过。没有把这些失败记为通过。

另外发现历史 M2 唯一约束故障夹具故意删除资格、覆盖 owner 后未恢复原 token。已根据数据库 CREATED/CANCELLED 记录及实际库存/Set 状态核对，恢复 4 条本地夹具的原 owner；不直接加库存或伪造完成标记。新 M2 测试增加该恢复步骤，并单项重跑通过。生产逻辑仍对不完整 owner 保留待释放任务，要求停流核对。

## 打包应用与真实定时任务

```bash
source .tools/runtime/env.sh
ORDER_PAYMENT_TIMEOUT_SECONDS=10 ORDER_CLOSE_DELAY_MS=1000 \
  java -jar target/hmdp-1.0-SNAPSHOT.jar
# 另一个终端导出相同本地环境
python3 scripts/verify-m3.py
```

脚本经 HTTP 登录、创建秒杀券、RabbitMQ 下两单，一单支付、一单等待 @Scheduled 自动关闭；核对双端库存，再由关单用户重新购买并支付。没有通过直接更新数据库伪造超时或手工调用扫描方法。

实际券 154：订单 637400162070691964 已支付，637400162070691965 自动关闭，重新购买 637400205020364926 已支付；最终 PAID=2、CLOSED=1、三单 version=1、MySQL/Redis 库存均为 0。原始脚本输出保存在测试摘要。

验证结束后按默认 900 秒有效期、10 秒扫描间隔重启应用，后端端口 18081，16:02:55 启动成功（耗时 4.401 秒）。最终待释放记录 0 条，主队列、DLQ、parking 均为空，主队列和 DLQ 各有 2 个消费者。便携环境配置文件在 `.tools/runtime/env.sh`，不纳入 Git；Docker Compose 本阶段只补迁移挂载，未实际运行 Compose。

下一步 M4：Caffeine + Redis 商户二级缓存、空值和随机 TTL、热点逻辑过期、Redisson 互斥重建、事务提交后失效。仍没有 JMeter 性能结果。
