# M7 单实例部署

当前工程适合一台测试服务器上的 Nginx + 一个 Java 实例 + MySQL + Redis + RabbitMQ。Caffeine 失效只协调当前实例，暂不扩应用副本。

## 全容器方式

要求服务器已安装 Docker Engine、Compose v2；构建机器有 Java 8 / Maven。所有命令从仓库根目录执行：

```bash
mvn -q -DskipTests package
cp .env.example .env
# 编辑 .env，设置自己的 MYSQL_PASSWORD、RABBITMQ_USER、RABBITMQ_PASSWORD
mkdir -p data/imgs
docker compose -f docker-compose.yml -f deploy/compose.server.yml config --quiet
docker compose -f docker-compose.yml -f deploy/compose.server.yml up -d --build
docker compose -f docker-compose.yml -f deploy/compose.server.yml ps
docker compose -f docker-compose.yml -f deploy/compose.server.yml logs --tail=100 app nginx
curl -f http://127.0.0.1:8080/api/shop/1
```

基础文件保留“中间件容器 + 宿主机 Java”开发方式。叠加 `deploy/compose.server.yml` 后增加 app 容器，Nginx 上游为 `app:8081`；默认应用 JVM `-Xms512m -Xmx512m -XX:+UseG1GC`，`.env` 中 `APP_JAVA_OPTIONS` 可覆盖。这个部署默认不等于当前本地压测 JVM 配置，性能报告必须记录实际值。

MySQL 首次空卷按 00→03 执行 SQL；已有卷不会重跑迁移。按主 README 的阶段迁移和 M6 历史点赞导入步骤升级，不能用 `down -v` 代替迁移。MySQL/Redis/RabbitMQ 数据使用命名卷，应用上传与 Nginx 共用 `data/imgs`。Compose 等待中间件健康后启动 Java，Nginx 到应用的 HTTP 就绪仍以实际接口返回成功为准。

默认所有宿主机映射端口只绑定 127.0.0.1，前端可用 SSH 隧道访问：

```bash
ssh -L 8080:127.0.0.1:8080 实际用户名@实际主机
```

浏览器访问 `http://localhost:8080`。需要独立负载机时，将 `.env` 的 `WEB_BIND` 设置为服务器实际测试网卡地址，配置防火墙仅允许负载机访问 `WEB_PORT`（默认 8080）。不在没有服务器资料时猜测地址。课程项目保留管理性质的券创建等演示接口，使用专用测试环境；本阶段没有新增正式后台权限系统或真实支付平台。

Nginx 配置是镜像入口程序处理的模板，只替换 `${NGINX_UPSTREAM}`。`/api/` 转发时去掉前缀、保留 authorization，覆盖 X-Real-IP；HTTP/1.1 上游复用，不重放下单 POST，连接/读取超时 3/10 秒。当前 USER 限流不依赖 IP。若接入 IP 维度，将实际直连代理精确 IP 配到应用 `RATE_LIMIT_TRUSTED_PROXIES`；不能把任意客户端发来的代理头当作身份。

## 宿主机 Java / 本机 Nginx

```bash
docker compose up -d mysql redis rabbitmq nginx
# 按 README 导出 MySQL/RabbitMQ 密码等变量
java -Xms512m -Xmx512m -XX:+UseG1GC -jar target/hmdp-1.0-SNAPSHOT.jar
```

基础 Compose 的 Nginx 上游默认 `host.docker.internal:8081`。若只用系统 Nginx，将 `deploy/nginx.conf` 中 `${NGINX_UPSTREAM}` 替换为 `127.0.0.1:8081`，按本机路径修改 root 与上传目录后放入 `http` 内的配置目录；先 `nginx -t`，再 reload。不要直接对配置执行无变量列表的 envsubst，以免清空 `$uri` 等 Nginx 变量。

## 压测与采集

按 [JMeter 说明](../jmeter/README.md) 准备独立数据，先验证小规模流程，再跑 500 线程。不要同时运行 M2 会修改 Exchange 的故障测试。压测期间保留版本、规格与 JVM 参数，例如在应用主机执行：

```bash
java -version
nproc
free -m
# JDK 环境；替换实际 Java PID
jcmd 实际PID VM.flags
jstat -gcutil 实际PID 1000
# Compose 环境另保存版本与资源观察；不输出包含密码的完整环境
 docker compose -f docker-compose.yml -f deploy/compose.server.yml stats --no-stream
```

本地与服务器实际验证均使用用户态便携中间件和 Nginx。Compose 配置已提供，但没有实际构建/运行镜像；实际服务器部署方式见下节，不将模板就绪当作容器验证通过。

## 本次实际服务器的用户态部署

使用 SSH config 中已配置的主机，部署目录为用户自己的 `~/hmdp-m7`。服务器为 Ubuntu20.04，未安装 Docker；采用项目专用 JDK8、MySQL8.0.28、Redis6.2.16、RabbitMQ3.9.13、Nginx1.18.0 与 JMeter5.6.3。便携工具仅存 `.tools`，不修改系统组件。Ubuntu22中间件二进制使用随包运行库和私有 ELF 解释器适配，不设置污染系统 shell 的全局 LD_LIBRARY_PATH。

`portable-configure.py` 配置已解压的专用工具包、通过 `mime.types` 保证CSS/JS等前端资源类型正确、生成服务器私有凭证（runtime/private.env，600）、Redis/RabbitMQ/Nginx配置。该脚本不是通用联网安装器，需先将验证过的工具包放到 `.tools`；不把工具二进制或凭证提交 Git。`portable-start.sh` 依次启动并校验中间件、应用与代理；`portable-stop.sh` 仅优雅停止该项目的进程。数据和 schema-ready 标记保留，已有环境启动不会重新导入表。

```bash
ssh SERVER_HOST
cd ~/hmdp-m7
bash deploy/portable-start.sh  # 仅全部服务已停止时；端口已占用会拒绝重复启动
source deploy/portable-env.sh
curl -f http://127.0.0.1:28080/api/shop/1
# 检查完需要停止时
bash deploy/portable-stop.sh
```

项目端口：MySQL23306、Redis26379、RabbitMQ AMQP25672/管理25673/分布式25674、EPMD24369、应用28081、Nginx28080，均配置回环。RabbitMQ AMQP与分布式端口必须分开；管理API出现不一定代表AMQP已就绪，启动脚本检查overview监听列表，压测再检查真实消费者。

服务进程使用CPU affinity0-7，JMeter使用8-11；这是共享服务器上的亲和性限制，**不是独占12个CPU的资源保证**。应用固定512MiB G1堆；RabbitMQ4调度器、内存水位1GiB；MySQL buffer pool512MiB。改变参数需记录在每轮environment.json中。

首次小规模测试显示HDD事务提交慢，按真实证据把整个项目runtime迁移到SSD上的 `/var/tmp/hmdp-test-runtime/runtime`，项目 `runtime` 是指向它的符号链接；原 `runtime-hdd-backup` 保留。这个目录用于本次测试，/var/tmp可能受系统清理策略影响，长期部署应申请正式SSD持久目录再迁移。不要将两个MySQL副本同时启动，也不要在运行时覆盖JAR；升级先优雅停止应用或使用新的独立版本文件。

压测和数据核对全部在服务器内部执行，数据库/Redis/RabbitMQ凭证没有导出到本地。只取回不含token的结果和原始JTL压缩包。前端通过SSH隧道访问：

```bash
ssh -N -L 28080:127.0.0.1:28080 SERVER_HOST
# 浏览器 http://localhost:28080
```

公开文档中的 `SERVER_HOST`、`SSH_PORT`、`/path/to/...` 和用户目录均为占位符，按自己的部署环境替换。实际服务器地址与个人目录不随公开仓库发布。
