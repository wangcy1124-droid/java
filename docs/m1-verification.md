# M1：Redis Lua 秒杀资格校验

> 历史阶段记录：2026-09-13 的当前实现、运行环境和验证方式见 [M2 记录](m2-verification.md)。旧 /tmp 环境已清理，勿再使用本文的旧 PID。

## 已实现

- `src/main/resources/seckill.lua`：只做原子资格校验与预扣，不再写 Stream。库存、资格 Set、时间元数据通过 KEYS 传入，用户 ID 通过 ARGV 传入。
- `SeckillResult`：0 成功，1 库存不足，2 重复下单，3 未准备就绪，4 未开始，5 已结束，6 兼容通道不可用。
- 使用 Redis TIME 校验 `[beginTime, endTime)`，避免应用节点时钟差异。MySQL LocalDateTime 按 `hmdp.seckill.zone`（默认 Asia/Shanghai）转换。
- 缺失库存、时间元数据或资格 Set 时拒绝预扣。资格 Set 内保留成员 `0`，用于区分“完整但尚无买家”和“key 丢失”，真实用户 ID 为正数；SCARD 统计买家时减去此成员。
- 新增秒杀券校验库存、起止时间和 ID，由事务提交回调预热，避免消费者先于券事务提交执行。预热失败记录 voucherId 并保持券不可抢购，数据库创建结果保留。
- `SeckillPreheatService` 启动时按券 ID 游标分批（100 条）预热。已有库存不覆盖；迁移健康 M0 数据时补时间元数据、合并数据库买家。已有 metadata 不重建，避免重启重置实时状态。
- 移除没有调用方的课程同步下单替代方法 `getResult`，统一从 Lua 入口下单。

## 与 M2 的边界

M1 将 Stream 写入从**资格脚本**移到 `seckill_stream_bridge.lua`。Java 将纯资格脚本包装为局部函数并与桥接脚本组合成一次 EVAL；因此预扣与 XADD 仍然原子执行，没有将两步拆成两个网络请求。桥接会在预扣前检查 Stream key 类型，避免常见 WRONGTYPE 导致预扣后入队失败。

**运行链路仍使用 Stream，尚未完成通道移除。** 这是为了保持本阶段可运行，符合 M0 结束时确定的衔接方式；M2 接通 RabbitMQ 后删除适配器和 Stream 消费者，同时改为资格成功后生成订单号。当前仍提前生成订单号，以供原子 XADD 使用。

Redis Lua 运行时错误不提供事务回滚；此桥接仅处理已知类型错误，不声称覆盖所有 Redis 故障。最终 confirm、有限重试、DLQ、补偿由 M2 完成。

## 预热恢复限制

有已初始化 metadata 时不会重新创建缺失库存。没有 metadata 且库存缺失时，只要存在资格 Set、数据库买家或任何 Stream 历史，就拒绝自动初始化（宁可让券暂不可抢购，也不从 MySQL 盲目覆盖未落库预扣）。因此老系统已有 Stream 历史时，某张缺失库存的新导入券也可能被保守拒绝；需要停流核对后处理。

不能仅凭数据库识别“Redis 全部丢失、订单还没有任何落库”的情况。本阶段不是在线灾难恢复方案，不能通过 FLUSHDB 验证所谓自动恢复；发生数据丢失应停流核对。M2 需要把持久消息和补偿边界一起落实。M0 → M1 迁移按单实例停流切换，不支持两版入口同时运行。

## 实际验证

2026-09-12，WSL，Java 8u504、MySQL 8.0.28、Redis 6.2.16，使用 M0 的专用环境。

- `source /tmp/hmdp-runtime/env.sh && mvn -q -o -DskipTests package`：退出码 0。
- 初次启动发现 `@Resource` 字段名 `voucherMapper` 误匹配普通券 Mapper，改为 `seckillVoucherMapper` 后启动成功。
- `22:33:36.003 Started HmDianPingApplication in 4.202 seconds`，端口 18081；启动预热了原 M0 券 10。
- `python3 scripts/verify-m1.py`：退出码 0。

实际输出：

```text
PASS 独立资格脚本、重复预热、库存/资格缺失、历史订单/消息、桥接异常无预扣
PASS 未开始/已结束/不存在券，拒绝后库存不变且无订单
PASS 多用户并发: requests=40 accepted=20 stock=0 voucherId=13
PASS 同用户并发: requests=40 accepted=1 stock=19 voucherId=14
PASS Stream 兼容通道已完成消费，pending=0；本次不是性能压测
```

并发测试均使用 20 工作线程、40 请求。断言成功数量、每一种拒绝原因、MySQL 订单及不同买家数量、Redis/MySQL 最终库存、资格人数和 pending=0。前置脚本还验证重复预热不会把预扣后的库存恢复，以及 Stream 错误类型时没有预扣。

日志：`/tmp/hmdp-runtime/app-m1.log`、`/tmp/hmdp-runtime/m1-verification.log`。前端仍为 `http://localhost:18080`，后端 `http://localhost:18081`。未执行 JMeter，没有性能指标结论。
