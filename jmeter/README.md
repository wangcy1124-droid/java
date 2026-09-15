# 秒杀入口 JMeter 验证

目标：服务器环境 500 并发用户，成功 TPS ≥800，P95 ≤300ms，错误率 <0.1%。本地 WSL 结果与服务器结果分别记录，不将本地数据写成服务器达标。

## 数据与指标口径

- `seckill-500.jmx` 使用标准 Thread Group，默认 500 线程、10 秒爬升、60 秒总时长，非 GUI 执行。Constant Throughput Timer 全线程组共享，默认目标到达速率900请求/秒（`--target-rps`），用于验证800TPS目标，不等于无上限饱和吞吐。
- 每次迭代读取一个全局共享 CSV 行：新用户 + 新 token + 同一张独立秒杀券。CSV 不循环，耗尽停止线程。500 指负载线程数量，不代表整场只用 500 个用户身份。
- `prepare.py` 批量创建真实 MySQL 用户，按当前登录 Hash 格式准备 Redis 会话（2 小时 TTL），并通过真实 `/user/me` 检查鉴权。登录准备不计入秒杀吞吐量；没有关闭应用的 USER 限流。
- 秒杀券通过现有创建接口预热，库存等于 CSV 行数。每轮新建数据，禁止清空库存或复用已经下单的券。独立开发/压测数据库使用，不向正式用户数据库灌入夹具。
- Groovy 断言同时要求 HTTP 200、`success=true` 和数字订单号。库存不足、重复下单、429、503 都算本场失败；不会把它们排除后美化结果。默认不重试 POST。
- TPS 是**受理请求**吞吐量，接口等待 publisher confirm，但不等待 MySQL 订单提交。结束后逐笔核对返回 orderId/userId 与落库订单，检查 MySQL/Redis 库存、资格、owner、delivery 状态以及队列排空。它不是支付 TPS，也不是已提交订单的即时 TPS。
- 全程统计包含爬升；另报告爬升后、结束前各去除2秒调度边缘的完整窗口样本（`--steady-margin`），TPS 分母为该窗口长度。P95/P99 使用 nearest-rank，不剔除失败或慢请求。`per-second.csv` 保留每秒样本和线程数。
- 数据耗尽、实际未维持 500 线程、持续窗口少于 30 秒、任何订单核对失败时，不判定达标。JMeter 调度与第一个样本的时间有小偏差，应连同逐秒记录审阅。

## 执行

要求 Java 8+、[Apache JMeter 5.6.3](https://jmeter.apache.org/usermanual/get-started.html)、Python 3、mysql CLI、redis-cli。组件行为参考 [CSV Data Set / Thread Group / JSR223](https://jmeter.apache.org/usermanual/component_reference.html)。使用非 GUI 压测，HTML 报告在施压结束后生成。

先按 [部署说明](../deploy/README.md) 启动应用及中间件。在**能访问测试 MySQL、Redis、RabbitMQ 管理端口**的终端设置与应用相同的环境变量：

```bash
export BASE_URL=http://127.0.0.1:8080/api
export MYSQL_HOST=127.0.0.1 MYSQL_PORT=3306 MYSQL_USER=root MYSQL_DATABASE=hmdp
export REDIS_HOST=127.0.0.1 REDIS_PORT=6379 REDIS_DATABASE=0
export RABBITMQ_MANAGEMENT_URL=http://127.0.0.1:15672 RABBITMQ_USER=hmdp
# MYSQL_PASSWORD、RABBITMQ_PASSWORD 使用已有本地环境或 read -rs 输入，勿写入仓库。
read -rs -p 'MySQL password: ' MYSQL_PASSWORD; export MYSQL_PASSWORD
read -rs -p 'RabbitMQ password: ' RABBITMQ_PASSWORD; export RABBITMQ_PASSWORD
export HEAP='-Xms512m -Xmx1g'
python3 jmeter/prepare.py --rows 100000 --out jmeter/data/run-01
python3 jmeter/run.py --data jmeter/data/run-01 --out jmeter/results/run-01 \
  --jmeter /opt/apache-jmeter-5.6.3/bin/jmeter \
  --threads 500 --ramp 10 --duration 60 --environment /tmp/test-environment.json
```

`/tmp/test-environment.json` 记录实际服务器规格、应用 JVM 参数、部署方式、连接池、Nginx、网络和中间件版本。例如：

```json
{
  "verified_server_environment": false,
  "environment": "填写服务器供应商/规格，以及负载机是否独立",
  "application": "实例数、Java版本、启动命令（不含凭证）",
  "jvm_flags": "填写实际 -Xms/-Xmx/GC 参数",
  "nginx": "版本、worker 数、worker_connections",
  "mysql": "版本、部署位置、磁盘类型",
  "redis": "版本、部署位置、AOF/fsync 策略",
  "rabbitmq": "版本、部署位置、队列类型",
  "application_pools": "Hikari/Redis/Tomcat/consumer concurrency/prefetch",
  "features": "限流开启，关单开启，支付超时900秒",
  "network": "负载机到服务器地址、网络/RTT情况"
}
```

远程负载机执行时，在服务端准备数据后仅复制 `jmeter/data/run-01` 到负载机，保护其中 token，不提交 CSV。将 manifest 的 `base_url` 调整为可达的 Nginx 地址；管理连接通过受控内网/SSH 隧道访问，不把数据库管理端口开放给互联网。也可在服务端执行整套流程，但须明确“施压端与被测服务同机”。服务端版本、JVM 信息必须人工填写真实值，脚本采集的是**运行脚本所在机器**的硬件信息。

先用另一份小数据 `--rows 200`，运行 `--threads 10 --ramp 1 --duration 5 --steady-margin 0` 验证流程；此轮会耗尽数据，只作为工具与订单核对验证。正式轮次重新准备 CSV。时长延长时增加行数，建议至少 `预估最高TPS × duration × 1.5`。每次独立数据准备串行执行，避免测试手机号区间冲突。

## 结果与核对

输出：

- `raw.jtl`：原始每请求耗时、状态、userId/voucherId/orderId；不记录 token。默认忽略 Git，可另行归档。
- `summary.json`：全程/窗口指标、业务失败样例及逐笔订单一致性检查。
- `parameters.json`、`environment.json`：实际参数、环境、日期。
- `queues.json`：施压和结束后的主队列/DLQ/parking ready、unacked、消费者数。
- `per-second.csv`、`console.txt`：逐秒证据及 JMeter 自身摘要。

脚本要求开始时主队列/DLQ 已清空且主队列存在消费者；既有 parking 只记录基线，禁止因测试擅自删除。结束等待最多 120 秒，可 `--drain-timeout` 调整，但总验证时间应短于支付超时，避免关单影响未支付库存核对。队列最终为空不等于没有出现过死信，专项 DLQ 语义已由 M2 验证，本场同时要求每笔受理订单落库。

脚本对功能失败返回非零；性能未达标仍保存完整真实结果，不自动改配置。`numeric_target_met` 要求完整窗口、500线程和所有核对同时通过；只有环境文件明确标记实际已验证的 `verified_server_environment=true` 且指标通过，`server_target_verified` 才为 true。人工必须复核环境与原始数据，不能给本地环境添加这个标记冒充服务器。

```bash
/opt/apache-jmeter-5.6.3/bin/jmeter -g jmeter/results/run-01/raw.jtl \
  -o jmeter/results/run-01/report
```

HTML 目录、未压缩原始 JTL 与 CSV 不默认提交；本次原始 `raw.jtl.gz` 留在本地与服务器，公开仓库保留 JSON/逐秒摘要。测试用户、券、订单不自动删除，Redis 测试登录会话按 TTL 到期；一轮中断也应换新数据目录。默认关单会在 15 分钟后处理测试未支付订单，不重置历史库存。

## 瓶颈定位

先读取真实 `summary.json` 和队列曲线：入口慢看 JVM/GC、Tomcat、Redis RTT、publisher confirm；入口快但队列持续增长看消费者、MySQL 库存热点行与磁盘提交。需要调优时每次只改有证据指向的一项，使用同规格新夹具重跑，记录修改前/项/后及所有失败，不能用增加队列积压换取“订单处理 TPS”。

当前阶段记录见 [M7 验证](../docs/m7-verification.md)。

若 120 秒后仍积压，原 `summary.json` 会保留核对失败，不再追加负载。可在后台正常消费/恢复后运行只读复核：

```bash
python3 jmeter/reconcile.py --result jmeter/results/run-01 --wait-seconds 0
```

它另存带日期的 settlement 文件，区分已创建、已关闭、创建前取消与尚未处理的受理请求。超过异步恢复期限（默认 5 分钟）可能取消尚未落库的占用，不能将最终库存恢复正常改写成“所有受理订单落库成功”。复核不修改原性能结果，也不删除队列或重置库存。

本地第一轮历史基线在加入速率整形和窗口边缘参数之前执行，保持原始 parameters/summary，不追溯修改成新方案。服务器复测使用新的明确速率参数，不能将两个不同环境与负载模型直接计算为调优提升百分比。

发布范围：GitHub只包含工程文件、汇总指标和逐秒统计；原始JTL（含模拟用户/订单标识）保留本地及服务器，不随公开仓库上传。原始结果未删除，README中的实际数字仍来源于这些真实记录。
