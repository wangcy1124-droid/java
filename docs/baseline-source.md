# M0 来源与版本选择

开发资料按用户工程目标、已验证代码、本地课程资料、指定参考仓库的顺序使用。工作站专用指令与PDF保留在本地。

- 本地 PDF：读取项目搭建、数据模型、缓存、秒杀异步化、消息队列章节。文件原先使用 GBK 字节文件名；读取后规范为 UTF-8 文件名 `黑马点评.pdf`，未修改 PDF 内容。PDF 中 Kafka 扩展和引用压测数据均未作为本项目实现或性能证据。
- 指定参考：[KNeegcyao/dianping](https://github.com/KNeegcyao/dianping)，master 的 `VoucherOrderServiceImpl` 已调用 `RabbitTemplate`，因此仅阅读参考，没有将该最终方案作为 baseline。
- baseline：[cs001020/hmdp](https://github.com/cs001020/hmdp)，master 的 `src/main` 和 `hmdp.sql`。实际 Lua 包含 `XADD stream.orders`，Service 使用 `XREADGROUP`、pending-list、`XACK`；包含用户、商户、优惠券、博客、关注、签到业务。
- 前端：同一仓库 init 分支 `src/main/resources/nginx-1.18.0/html/hmdp`，仅导入静态页面与资源。
- 获取日期：2026-09-12；使用公开分支源码快照，不导入第三方 Git 元数据、IDE 文件、构建产物、Windows Nginx 或上游依赖个人环境的测试与 token 数据。

PDF 仅作为本地开发参考，不随仓库发布，也不是构建或运行依赖。项目运行所需代码、前端和 SQL 均包含在仓库中。

## 版本

维持选定 baseline 已有 Java 8 / Spring Boot 2.7.4 / MyBatis-Plus 3.5.2 / Hutool 5.8.8 / Redisson 3.17.7，未为了升级而升级。AGENTS.md 的 2.3.x / 3.4.x 是优先选择，允许维持导入 baseline 的明确版本。本次构建与真实运行结果用于验证该选择。

## M0 必要适配

- 环境变量配置 MySQL、Redis、上传路径；Redisson 与 Spring Redis 使用同一 database/password。
- Stream 自动创建 `g1` 消费组，保留 `c1` 单消费者；重启先处理该消费者 pending；线程随应用关闭停止。
- 修复课程实现中数据库扣库存返回值未检查、重复投递会重复扣库存的问题；依然使用原事务代理和 Redisson 用户锁。
- 商户采用课程已有的 Redis 穿透缓存回源路径，避免未预热逻辑过期 key 时总是返回不存在；修正空值编码及 TTL。
- 修复取关传入 Long 导致 StringRedisTemplate 序列化失败，补齐登出；移除无用 JDK 内部类导入和 IDE 注解。
- SQL 去掉 DROP TABLE，仅用于空数据库首次导入；不自动重置已有数据库。

## 保留的阶段边界

这是单实例课程 baseline。Stream 异常消息仍按课程 pending-list 重试，持续失败会阻塞；M2 将替换为 RabbitMQ 的有限重试、DLQ 与补偿。当前不存在消息死信补偿、支付关单、Caffeine 或通用限流实现。

秒杀券新增接口会预热新券库存；M1 需完善已有券的库存/资格初始化和 Redis 时间窗口校验。Redis 丢失后不能只重置库存而忽略已下单用户及未消费消息。M0 不声称跨故障库存一致性已完成。

数据库尚未添加 `(user_id, voucher_id)` 唯一索引；保留课程状态字段，不宣称已实现 M3 生命周期。关注/点赞保留课程 Redis 数据结构，M6 再集中验证并发与恢复边界。
