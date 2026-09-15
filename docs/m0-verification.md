# M0 实际验证记录

> 历史阶段记录：2026-09-13 的当前实现、运行环境和验证方式见 [M2 记录](m2-verification.md)。旧 /tmp 环境已清理，勿再使用本文的旧 PID。

日期：2026-09-12，Asia/Shanghai。只记录本次执行结果，不是压测结果。

## 环境与构建

- WSL/Linux x86_64；Java Temurin 1.8.0_504，Maven 3.9.9。
- MySQL 8.0.28，回环地址 13306；Redis 6.2.16，回环地址 16379，开启 AOF。
- Spring Boot 2.7.4，应用端口 18081；Nginx 1.18.0，前端端口 18080。
- 系统原先未安装 Java、Maven、MySQL、Redis。工具与服务数据位于 `/tmp/hmdp-tools`、`/tmp/hmdp-m2`、`/tmp/hmdp-runtime`，没有修改系统服务。
- 首次编译发现上游未使用的 `com.sun.xml.internal.ws.policy.privateutil.PolicyUtils` 导入，删除后构建通过。
- 最终构建：`mvn -q -o -Dmaven.repo.local=/tmp/hmdp-m2 -DskipTests package`，退出码 0。
- 产物：`target/hmdp-1.0-SNAPSHOT.jar`，约 47 MB。
- `sql/00_base.sql` 导入新建空库成功，11 张课程业务表。
- 没有导入上游依赖个人环境的测试；构建成功不等同于测试通过，下述验证为真实外部服务的 Python/HTTP 集成断言。

## 启动

首次启动日志：

```text
2026-09-12 22:12:46.418 Tomcat started on port(s): 18081 (http)
2026-09-12 22:12:46.426 Started HmDianPingApplication in 3.968 seconds (JVM running for 4.323)
```

向全新 Redis 自动创建 `stream.orders`、消费组 `g1`，没有要求预先手工建组。

## 原业务验证

执行 `scripts/verify-m0.py`，退出码 0：

```text
PASS 商户冷缓存/命中/空值、分类、热门博客、优惠券列表
PASS 验证码登录、token、用户信息、签到
PASS 发笔记、ZSet 点赞/取消、Set 关注/取关/共同关注
PASS Stream 预扣/落库/ACK、重复下单、重复消息、库存不足、不存在券
EVIDENCE voucherId=10 orderId=636754787399892993 MySQL orders=2 stock=0 Redis stock=0 pending=0
PASS 登出后 token 失效
```

该测试券初始库存 2。第一位用户成功，重复请求被拒；重投相同订单后 MySQL 库存仍为 1；第二位用户成功，最终 Redis/MySQL 库存均为 0，订单数量为 2，消费组 pending 为 0。未进行性能或大并发结论推断。

## 重启与 pending 恢复

1. 对本次 Java 进程发送 SIGTERM，进程退出（143），Hikari 正常关闭，无消费者导致进程挂住。
2. 停机期间将上述已落库订单重新 XADD，并用 `XREADGROUP GROUP g1 c1` 将其置为 pending，确认 pending=1。
3. 启动同一 jar，已有消费组未导致 BUSYGROUP 启动失败，先恢复 pending。

```text
2026-09-12 22:15:47.777 Started HmDianPingApplication in 4.0 seconds (JVM running for 4.34)
PASS restart recovery: pending 1 -> 0, orders=2, MySQL stock=0, Redis stock=0
```

这是相同消费身份 `c1` 的恢复验证，不声称实现了多实例消费者接管或无限故障处理。

## 前端与 GEO

执行 `scripts/init-shop-geo.py`：`Initialized 14 shop GEO entries`。

临时 Nginx 使用 `deploy/nginx.conf` 的相同路由规则，仅替换宿主机路径、监听端口与上游地址。实际 HTTP 验证：

```text
PASS HTTP 200 /
PASS HTTP 200 /js/common.js
PASS HTTP 200 /imgs/blogs/blog1.jpg
PASS HTTP 200 /api/shop/1
PASS HTTP 200 /api/shop/of/type?typeId=1&current=1&x=120.149192&y=30.316078
```

两条 API 还断言 `success=true` 和非空数据。没有运行浏览器点击测试，也没有在 Docker 中运行 Compose。

## 当前会话访问与复验

本次进程保留运行，可访问：

- 前端：`http://localhost:18080`
- 后端：`http://localhost:18081/shop/1`

当前 WSL 终端可加载临时工具环境：

```bash
source /tmp/hmdp-runtime/env.sh
mvn -q -o -DskipTests package
python3 scripts/verify-m0.py
```

工具环境明确配置了本次专用数据库端口；不要对其他业务库直接运行验证脚本。首次未通过的验证是系统 HTTP 代理导致 502，脚本已改为直接连接目标服务，随后完整通过。

应用日志：`/tmp/hmdp-runtime/app.log`、`app-restart.log`；MySQL 日志 `mysql.log`。当前 Java PID 86067、MySQL PID 81218、Redis PID 84571；这些 PID 只代表本次会话，不应在后续系统重启后照抄使用。Nginx PID 见 `/tmp/hmdp-runtime/nginx.pid`。

`/tmp` 工具和数据不是永久安装，系统清理后需按 README 用标准 Java/Maven/MySQL/Redis 环境启动。仓库已初始化 main 并配置用户指定 origin，未创建提交或推送。
