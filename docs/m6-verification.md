# M6：点赞、关注与共同关注

完成及验证日期：2026-09-14，Asia/Shanghai。先阅读本地《黑马点评.pdf》320～326、331～333 页，保留课程 ZSet/Set 业务和接口兼容性，补齐并发、持久化与查询边界。

## 实际修改

| 文件 | 作用 |
| --- | --- |
| `sql/03_social.sql` | 点赞关系主键与时间索引；关注关系唯一索引和粉丝查询索引 |
| `scripts/migrate-social-likes.py` | 停流升级时导入旧 Redis 点赞用户/时间，保留既有总数 |
| `entity/BlogLike.java`、`mapper/SocialMapper.java` | 点赞身份、SQL 行锁、条件计数、关系增删及批量加载 |
| `service/SocialTransactionService.java` | 点赞关系/计数同事务；关注幂等写入 |
| `service/SocialInteractionService.java` | Redisson 锁覆盖事务提交与缓存同步，校验关注目标 |
| `service/SocialProjectionService.java`、`resources/social_projection.lua` | ZSet/Set 增量同步、完整快照标记、冷重建及失败处理 |
| `service/impl/BlogServiceImpl.java` | Top 5 排序、批量笔记/作者查询、同毫秒滚动分页；新笔记初始化计数 |
| `service/impl/FollowServiceImpl.java` | SINTER 共同关注、丢失 Set 恢复、一次批量查用户 |
| `controller/BlogController.java` | 新增显式 true/false 点赞接口，旧 toggle 路径保留 |
| `frontend/index.html`、`blog-detail.html`、`info.html` | 点赞使用目标状态，重复发送同状态保持幂等 |
| `application.yml`、`docker-compose.yml` | 社交快照 TTL 和首次初始化迁移挂载 |
| `src/test/java/com/hmdp/SocialInteractionIT.java`、`scripts/verify-m6.py` | 专项集成与打包应用 HTTP 验证 |

表中 Java 路径以 `src/main/java/com/hmdp/` 为根，资源位于 `src/main/`。

## 业务与并发边界

- 点赞身份持久化到 `tb_blog_like(blog_id,user_id,liked_at)`；MySQL 锁住笔记行，再增删关系与计数，取消使用 `liked>0` 条件；其中任一步异常整体回滚。
- `PUT /blog/like/{id}/{true|false}` 是幂等目标状态；重复点赞不更新首次点赞时间、重复取消不再减数。旧 `PUT /blog/like/{id}` 是切换状态接口，保留每次切换语义，不宣称重试幂等。
- 关注 `(user_id,follow_user_id)` 唯一，重复关注/取关不增加多条关系。拒绝自关注和新增指向不存在用户的关系。
- 点赞按笔记、关注按发起者获取 Redisson 锁；watchdog 管理租期，finally 同线程释放。锁覆盖业务事务返回和 Redis 同步，避免正常竞争下旧同步覆盖新同步。MySQL 行锁/唯一约束独立保护关系与计数。
- Redis 点赞 member=userId，score=点赞毫秒时间；`ZRANGE 1 5` 跳过内部 `0` 哨兵（score=-1），精确返回最早五位真实用户；批量 SQL 保持 Redis 顺序。同毫秒按 Redis 自身 member 排序作为稳定次序，不声称它就是同毫秒内的真实先后。
- Follow Set 的 `0` 哨兵区分完整空集合和丢失的数据，SINTER 后过滤该成员，再一次批量查用户。不会逐人查数据库。
- 无完整标记、key 丢失或过期时，在同一把锁下从 MySQL 关系表重建；Lua 原子替换，读者不会看到逐项填充的半份快照。
- 快照默认 300 秒 TTL；增量写不延长整个快照寿命。Redis 同步失败时保留已提交关系，记日志并尝试删缓存；删除也失败则让固定快照过期后重建。因此即使持续互动，也不会无限刷新一份漏更新的快照。
- 这仍是 MySQL 提交后更新 Redis，允许短暂不一致；没有加入社交 Outbox 或宣称跨存储原子提交。无法取得 Redis 锁时不直接绕过锁写数据库。缓存恢复依赖 MySQL 关系仍在；读共同关注不是跨两个用户事务的数据库快照。
- Feed 保留课程发布后的同步推送方式，本阶段修复读侧 N+1 和同毫秒跨页 offset，不宣称消息推送具备投递重试。新笔记的计数固定从 0 开始，不能由客户端上传。

## 迁移与旧数据

旧应用停流后，执行 `03_social.sql`，然后导出正确的 MySQL/Redis 环境并运行 `python3 scripts/migrate-social-likes.py`，最后启动 M6。存在重复关注时唯一约束失败，迁移不会自动删除关系。

点赞导入在关系表为空时执行，检查 Redis 用户、笔记、时间和总数；非空则拒绝重复导入。现有已知用户和毫秒时间原样保存，不再次增加 liked。课程自带的一部分点赞只有总数、没有具体身份；保留总数，不凭空生成用户关系。新笔记关系从 0 开始维护，迁移后的旧榜单只列出已知用户。

本次本地库不存在重复关注。为实际验证旧格式升级，在停流后建立笔记 24 的明确迁移夹具：用户 1、liked=1、无完整标记的旧 ZSet。迁移输出 `Imported 1 known Redis likes`；原总数不变。专项测试另用独立夹具验证已导入关系在旧格式升级及丢失 ZSet 后均保留时间，不依赖这条手工迁移夹具存在。

## 构建和专项结果

使用便携 Java 8u504、Maven 3.9.9、MySQL 8.0.28（13306）、Redis 6.2.16（16379）和 RabbitMQ 3.9.13（AMQP 15672），没有清库。Compose 仅新增 SQL 挂载，未实际部署 Compose。

```bash
source .tools/runtime/env.sh
mvn -q -DskipTests package
mvn -q -Dtest=SocialInteractionIT test
```

构建成功；**12 项测试全部通过，0 失败、0 错误、0 跳过，耗时 10.621 秒**。

| 场景 | 实际结果 |
| --- | --- |
| 同用户显式点赞/取消 | 30 次并发点赞只生成一条关系、计数 1；30 次取消回到 0；重复点赞时间不变 |
| 多用户及旧 toggle | 12 用户点赞，关系和计数均 12；同用户 20 次 toggle 后回到未点赞，无负数 |
| Top 5 | 7 用户按可核对时间排列，仅返回最早 5 位，顺序正确，用户 SQL 一次 |
| 关注重复与目标校验 | 30 次关注仅一条，直接重复 INSERT 被唯一索引拒绝，30 次取关归零；自关注/不存在目标拒绝 |
| 关注/取关竞争 | 24 次混合并发，最终只有 0/1 条关系且与 Redis 一致 |
| 共同关注恢复 | 删除双方 Set 后恢复正确交集，一次批量查询用户；取关后立即移出交集；不返回哨兵 |
| 旧点赞格式与重建 | 已导入的关系时间在无标记旧 ZSet 升级及 key 丢失重建后保持一致 |
| SQL 事务失败 | 注入计数更新异常，关系插入和计数整体回滚，不写 Redis，锁释放 |
| Redis 同步失败 | 指定快照写入方法注入失败，点赞/关注数据库提交保留、缓存被删除，恢复后读侧重建 |
| 固定快照 TTL | 增量点赞不延长之前快照的 TTL |
| Feed 分页/批量 | 5 条同毫秒动态分 3 页读完，无重复，offset 最终为 5；每页笔记和作者各一次批量查询 |
| 新笔记/不存在笔记 | 客户端传 999 的计数被初始化为 0；不存在笔记点赞失败 |

故障用例明确通过 Spy 注入 SQL 或 Redis 快照写入步骤异常；正常关系查询、数据库事务、Redis 命令和 Redisson 锁实际运行。记录真实 ERROR 仅对应预期注入的同步故障，不把它们静默吞掉。

三个修改的 HTML 内联脚本均通过 `node --check`；未执行浏览器端到端测试。

## 打包应用结果

应用于 17:32:14 启动成功，耗时 4.904 秒，后端 `http://localhost:18081`。M5 限流、M3 默认订单有效期保持开启。

```bash
source .tools/runtime/env.sh
java -jar target/hmdp-1.0-SNAPSHOT.jar
# 另一个终端加载相同环境
python3 scripts/verify-m6.py
```

脚本真实创建用户 1332、1333、作者 1334 和笔记 39，验证重复关注、共同关注、删除 Set 后重建、幂等点赞/取消、时间顺序、删除 ZSet 后重建、旧 toggle 路径以及关注动态。最终笔记 liked=0、关系数=0、共同关注为空；应用日志无新增业务 ERROR。

完整输出见 [m6-test-results.txt](m6-test-results.txt)。下一步 M7 是 Nginx/服务器部署、真实 JMeter 500 并发测试、按结果调优和最终文档；当前仍没有 TPS/P95/错误率的性能结论。
