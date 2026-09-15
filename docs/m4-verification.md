# M4：商户二级缓存与更新失效

完成及验证日期：2026-09-14，Asia/Shanghai。开发前阅读本地《黑马点评.pdf》37～45 页缓存章节，按根目录 AGENTS.md 落地单应用实例方案。保留 Java 8 / Spring Boot 2.7.4，新增 Java 8 兼容的 Caffeine 2.9.3。

## 实际修改

| 文件 | 作用 |
| --- | --- |
| `src/main/java/com/hmdp/service/ShopCacheService.java` | Caffeine → Redis → MySQL；空值、随机 TTL、热点逻辑过期；Redisson 冷加载/异步重建；提交后失效 |
| `src/main/java/com/hmdp/config/ShopCacheProperties.java` | 容量、时间、热点 ID、锁等待配置 |
| `src/main/java/com/hmdp/dto/ShopCacheEntry.java` | 热点 data/expireTime 封装 |
| `src/main/java/com/hmdp/service/impl/ShopServiceImpl.java` | 商户详情接入二级缓存，更新通过 afterCommit 失效；不存在商户更新返回失败 |
| `pom.xml`、`src/main/resources/application.yml` | Caffeine 依赖与环境变量覆盖 |
| `src/test/java/com/hmdp/ShopCacheIT.java` | 9 项真实 MySQL/Redis 专项测试与指定故障注入 |
| `scripts/verify-m4.py` | 打包应用的 HTTP 商户查询/更新验证 |
| `README.md`、`docs/resume-evidence.md` | 查询链路、配置、部署边界及简历证据 |

移除了未再使用的 `CacheClient.java` 和 ShopServiceImpl 中注释掉的旧缓存示例。没有数据库迁移；既有普通 Shop JSON 可继续读取，商户 ID 被配置为热点后会异步升级为逻辑过期封装。M0～M3 文档作为历史记录保留。

## 实现与边界

- L1 只存按 ID 查询的非空商户，maximumSize=10000，expireAfterWrite=5 秒；返回 Shop 副本避免共享对象被调用方改写。
- 普通 Redis 缓存 TTL=1800+random(0,300) 秒，保留课程 Shop JSON 格式。
- 查不到商户时 Redis 写空字符串，TTL=120 秒；空值不放进 L1。
- 默认热点为 ID=1，可通过 `SHOP_CACHE_HOT_IDS=1,2,3` 在启动时配置。热点逻辑 TTL=60 秒，物理 TTL=300+random(0,300) 秒；逻辑过期立即返回旧值，物理丢失则按冷缓存加载。
- 冷缓存使用 Redisson `tryLock(2000ms)`，获取后再次查询 Redis，避免并发重复回源。超时返回业务失败，未引入无限等待/无限递归重试。
- 热点重建使用 2 个线程、64 长度队列，同一 ID 本机合并为一个任务。获取 Redisson 锁的后台线程二次检查 Redis，再查 MySQL 刷新 L2/L1；watchdog 管理锁租期，同线程 finally 解锁。队列满时保留旧值、记录警告，后续请求重试。
- 商户更新先提交 MySQL，再失效 Redis 和本实例 L1。回滚不触发删除。Redis 删除失败记录 shopId 和异常，并确保 L1 仍被删除；普通缓存可能一直保留到其物理 TTL 到期，不能宣称删除失败后立即一致。
- 使用固定 256 个本地分段读写锁：查询与后台重建持读锁直到完成填充，afterCommit 失效持写锁。这样已读到旧 DB 值的重建必须先写完，再由更新线程清缓存；不会在更新接口返回后把那次旧查询回填回来。写锁公平排队避免被连续读请求饿死，不在更新数据库事务内等待缓存锁。
- 单实例保证本地失效顺序。Redisson 可协调多个实例重建，但不等于跨实例 Caffeine 失效；多实例部署须另补通知机制。列表/GEO 查询保持原路径。
- Redis 整体故障时已有 L1 可以命中；L1 miss 不绕过 Redis/锁无限打数据库。热点 DB 重建失败保留旧缓存并释放锁，后续请求可重试。允许短暂旧值，不宣称强一致性或无限故障可用。

配置项和默认值完整列在 README 的商户缓存表中。

## 专项构建与测试

本地使用 Java 8u504、Maven 3.9.9、MySQL 8.0.28（13306）、Redis 6.2.16（16379）；Spring 上下文也连接已有 RabbitMQ（AMQP 15672）。测试启动前停止旧应用，未清库。

```bash
source .tools/runtime/env.sh
mvn -q -DskipTests package
mvn -q -Dtest=ShopCacheIT test
```

最终结果：**9 项，0 失败、0 错误、0 跳过，耗时 9.439 秒**。测试将 TTL/容量缩小便于验证，不作为性能压测。

| 验证 | 实际断言 |
| --- | --- |
| L1 / L2 / DB | 冷查询 DB 一次；L1 命中不调用 Redis；清 L1 后命中 Redis 不再查 DB；修改返回对象不改变共享缓存 |
| 空值和 TTL | 不存在 ID 两次查询只查一次 DB；Redis 空字符串短 TTL；普通 TTL 在配置的 base+jitter 区间内 |
| L1 容量与过期 | 1 秒 L1 TTL 过期后重新走 L2；maximumSize=3，加载多个商户后容量不超过 3 |
| 冷缓存并发 | 40 次调用、16 工作线程，仅回源一次，结束后 Redisson 锁释放 |
| 热点并发 | 阻塞真实 DB 重建时 40 次读请求仍全部返回旧值；释放 DB 阻塞后缓存变新，仅回源一次 |
| 重建失败 | 注入查询异常，旧值保留、锁释放，后续真实 DB 查询能刷新 |
| 更新提交/回滚 | 外层事务提交前缓存仍在；回滚保留原缓存与原 DB；提交后 L1/L2 均删除 |
| 更新/旧重建竞态 | 暂停已经读出旧 DB 值的后台任务，让更新先提交；更新失效等待旧任务完成，之后 L1/L2 无旧值，下次查询获得新值 |
| 删除失败 | 注入 Redis delete 异常，数据库更新仍提交、L1 删除、L2 保留有限 TTL |

首次测试有两个夹具因对 MyBatis 接口代理调用 `callRealMethod()` 失败，改为 SqlSession 执行真实查询后全部通过。指定异常/阻塞用于制造并发窗口；数据库、Redis 和 Redisson 锁均真实运行。未重跑与 M4 无关的全量测试，没有 SHA 核验或性能数字。

## 打包应用验证

```bash
source .tools/runtime/env.sh
java -jar target/hmdp-1.0-SNAPSHOT.jar
# 另一个终端加载相同环境
python3 scripts/verify-m4.py
```

应用于 16:52:26 启动成功，耗时 4.48 秒，后端 `http://localhost:18081`。运行时继续采用订单默认 15 分钟有效期和 10 秒关单扫描。

HTTP 验证实际新增商户 43，普通缓存观测 TTL=1980 秒；更新后 Redis key 删除，随后的详情返回更新后的名称，证明没有返回旧 L1。不存在商户命中空值短 TTL，默认热点商户 1 使用 data/expireTime 封装并有有限物理 TTL。没有修改课程商户 1 的数据库数据。原始输出见 [m4-test-results.txt](m4-test-results.txt)。

下一步 M5：自定义注解、AOP、Redis ZSet/Lua 滑动窗口限流，支持 API/IP/USER 三维度并接入秒杀入口。JMeter 的 TPS、P95、错误率仍是 M7 待实测目标。
