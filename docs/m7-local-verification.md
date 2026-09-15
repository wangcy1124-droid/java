# M7 本地阶段历史验证记录

本文件保留提供服务器连接信息之前的 WSL 验证与后续结算。最终服务器结果以 [M7 汇总](m7-verification.md) 为准。

日期：2026-09-14，Asia/Shanghai。

**状态：部署与压测工具已实现，本地验证已执行；性能验收未通过，服务器部署/压测尚未完成。** 当前未提供服务器 SSH 连接信息，本地没有 Docker。没有将 Compose 模板、WSL 结果写成服务器部署成功或性能达标。

## 工程变化

- `deploy/Dockerfile`、`deploy/compose.server.yml`：单 Java 实例完整容器部署入口，依赖中间件健康检查，上传目录共享。
- `docker-compose.yml`：补 Redis/RabbitMQ healthcheck、MySQL 时区、Web 绑定变量，修复 RabbitMQ 用户默认值插值语法。
- `deploy/nginx.conf`：模板上游、HTTP/1.1 keepalive、连接/读取超时，禁止重放下单 POST。保留 `/api/`、静态页面和上传图片路径。
- `application.yml`：Hikari 最大连接、RabbitMQ concurrency/prefetch 支持环境覆盖，**默认值仍为 10/2/10**。这是服务器复测配置入口，未声称改参数后性能提高。
- `jmeter/seckill-500.jmx`、`assert-order.groovy`：真实秒杀 POST、全局 CSV 游标、每次新用户、HTTP 与业务双重断言，EOF 不循环。
- `jmeter/prepare.py`：独立券和真实测试用户，短期 Redis 登录会话，预热库存并检查鉴权。不会重置原有券、清数据库或清队列。
- `jmeter/run.py`：队列前置检查、非 GUI 施压、原始 JTL、指标统计、逐秒采样、逐笔订单与库存核对，失败保留证据并非零退出。
- `jmeter/reconcile.py`：超时后只读复核，区分已创建/关闭、创建前取消、未处理；不会覆盖最初的失败结果。
- 部署命令见 [deploy/README](../deploy/README.md)，完整流程见 [jmeter/README](../jmeter/README.md)。

开发前定位 PDF 的 JMeter 与秒杀章节（第 6～8、50～51、60～61、301、309 页）；课程/资料中的性能数字不作为本仓库数据。

## 本地环境

负载机与应用/中间件同一 WSL2，Intel Core i7-14650HX，24 逻辑 CPU，MemTotal 16,223,928 KiB，Linux 6.18.33.2-microsoft-standard-WSL2。

| 组件 | 实际配置 |
|---|---|
| 应用 | Java 8u504 / Spring Boot 2.7.4，单进程，18081 |
| 应用 JVM | 未显式指定堆；实际 InitialHeapSize=260046848、MaxHeapSize=4154458112、ParallelGC |
| JMeter | 5.6.3 / Java 8u504，`HEAP=-Xms512m -Xmx1g` |
| Nginx | 1.18.0 便携 Ubuntu binary，2 workers、worker_connections=2048、upstream keepalive=64，18080 |
| MySQL | 8.0.28，WSL 文件系统，13306，buffer pool=128MiB、flush_log_at_trx_commit=1、sync_binlog=1 |
| Redis | 6.2.16，16379，AOF 开启、everysec |
| RabbitMQ | 3.9.13，15672，持久经典队列/持久消息，消费者2、prefetch10 |
| 其他 | Hikari max10，Tomcat 默认 max200；限流开启；支付超时900秒，关单开启；异步恢复期限300秒 |

完整机器和参数记录在 [environment.json](../jmeter/results/local-500-baseline/environment.json)、[parameters.json](../jmeter/results/local-500-baseline/parameters.json)。容器模板默认 512MiB G1 堆，与本次本机 Java 参数不同，不能混用环境描述。

中断恢复时发现 MySQL/Nginx 未运行，先恢复原 MySQL 数据目录及 Nginx，确认代理接口 200 后才开始压测。之前的连接失败不混入正式计时样本；没有清空数据。

## 实际验证

1. `mvn -q -DskipTests package` 成功。Python 脚本编译检查通过，两个 Compose YAML 解析通过；本地 Nginx `-t` 通过，`GET /api/shop/1` 返回 200。
2. 小规模 JMeter 流程：10 线程，200 行独立数据，券157。200 次受理、200 笔订单，MySQL/Redis 库存0、资格200，全部核对通过。数据耗尽，**仅为流程验证**。见 [small summary](../jmeter/results/local-check/summary.json)。
3. 反向断言验证：6 次请求中5次 HTTP 200 业务拒绝、1次 HTTP 429，全部标记失败，符合预期；未把 HTTP 200 当作购买成功。见 [assertion summary](../jmeter/results/assertion-negative/summary.json)。
4. 500 线程基线：10秒爬升、计划60秒，10万用户/库存，券158；2026-09-14 22:21:14 启动 JMeter，约22:22:00结束。见下表。

| 指标 | 实际结果 |
|---|---:|
| 请求数 / 受理数 | 100,000 / 100,000 |
| HTTP 计时跨度（首请求开始至末请求结束） | 45.272秒 |
| 全程受理 TPS | 2208.871 |
| P95 / P99 | 304ms / 384ms |
| 平均 / 最大耗时 | 200.325ms / 528ms |
| HTTP/业务错误率 | 0% |
| 最大 JMeter 线程 | 500 |
| 数据是否提前耗尽 | 是 |
| 采样主队列最高 ready+unacked | 96,574 |
| JMeter 结束后核对等待 | 121.399秒 |
| 核对时已读取订单数 | 27,796 |
| 当时 Redis / MySQL 剩余库存 | 0 / 72,193 |
| 当时是否全部落库、队列排空 | 否 |

JMeter console 按自己的起止边界报告约2197.4/s，脚本按 JTL 首尾样本跨度报告2208.871/s；两份原始证据均保留，不选择更好看的数字。队列管理指标有刷新延迟，SQL/Redis 查询也非跨存储同一快照，因此持续消费时订单数、库存与 delivery 数存在小范围采样时差。

10万行在计划结束前耗尽，不能把 `steady` 中以计划50秒为分母计算的1694.06/s当作持续50秒稳态成绩；最后线程降到0。即使只看入口，P95 304ms也未达到300ms。更重要的是大量已受理请求尚未落库，库存核对失败，因此脚本按预期退出1，`local_numeric_target_met=false`、`server_target_verified=false`。

完整证据：[summary](../jmeter/results/local-500-baseline/summary.json)、[逐秒](../jmeter/results/local-500-baseline/per-second.csv)、[队列曲线](../jmeter/results/local-500-baseline/queues.json)、原始JTL（本地/服务器归档；公开仓库提供[汇总结果](../jmeter/results/local-500-baseline/summary.json)）。压缩原始文件约1.5MB，保留真实每请求耗时/订单号，不含 token。HTML 报告在本地 `jmeter/results/local-500-baseline/report/index.html`，不默认提交。

## 瓶颈证据与取舍

本轮结束后的消费者线程栈：一个 `orderCreate` 线程等待 `tb_seckill_voucher` 条件 UPDATE，另一个等待 MySQL commit；SHOW PROCESSLIST 同样看到 updating / waiting for handler commit。该 MySQL 实例启动以来采样的平均 commit 耗时3.32ms，累计commit41.90秒、InnoDB日志文件等待54.28秒。这些是实例累计诊断数据，**不是每个秒杀请求的精确归因**。

单券库存行更新与事务提交是下一步重点。入口速度远高于消费速度，增加入口压力只会继续积压，因此本轮后停止追加负载。保留 fsync、ACK、事务和恢复期限，不通过关闭持久化或放宽核对将失败改成成功。未做没有前后对照的连接池/GC 参数调优，也没有虚构“提升百分比”。

先让既有消息正常处理，再用只读 settlement 核对。超过5分钟还未落库的占用可能被恢复任务取消并补偿；这样的最终一致性不等于所有受理订单都成功。原失败 summary 不会被后续核对覆盖。

## M7 尚需完成

1. 提供真实测试服务器连接及资源规格，按部署文档运行 Compose/宿主机方案，实际验证镜像、网络、数据卷与代理。
2. 用独立负载机优先复测；准备至少 `预估峰值TPS × duration × 1.5` 行，不再以10万行冒充完整60秒测试；但必须先确认消费能力，避免重复制造未处理积压。
3. 在目标服务器采集 MySQL 提交/磁盘等待、库存行锁、消费者吞吐和 GC；根据证据逐项比较配置，必要时再评估缩短库存事务锁区间。不能直接靠提高消费者数量解决同一库存行的串行提交限制。
4. 完整500线程持续窗口、P95、错误率与异步落库核对全部通过后，才写服务器性能结论。目前简历只能写已实现和已功能验证的 M1～M6 能力，不能写目标性能已达到。


## 本地后续结算补充

22:44:41只读复核：98,800笔已创建，1,200笔在创建前取消；其中1,100笔已按超时规则关闭、97,700笔待支付。未归属请求0，队列全部清空，MySQL/Redis库存均2,300，资格/owner均97,700，释放待重试0。见 [最终快照](../jmeter/results/local-500-baseline/settlement-20260914-224441.json)。这证明最终处理可核对，**不改变本轮性能失败、并非10万受理全部落库**的结论。

后续结算期间一次构建覆盖了当时仍运行的target JAR，消费进程随后发生类加载错误并退出；已改用独立的 `.tools/runtime/hmdp-m7-run.jar` 恢复并完成结算。该问题发生在最初121秒核对失败之后，不影响原始性能样本，但影响后续排空时间，因此不将最终耗时作为纯消费性能指标。部署说明已要求运行时不覆盖JAR。
