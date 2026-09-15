# M7 服务器部署与性能验证

实际服务器：SSH config 指向的 `SERVER_HOST:SSH_PORT`，用户 `部署用户`，部署在 `/path/to/hmdp-m7`。本阶段完成用户态部署、Nginx、真实 JMeter 方案、业务断言、异步订单/库存核对和证据归档。Compose 另有模板，未实际运行镜像。

## 环境与负载口径

Ubuntu20.04.6，80逻辑CPU、约256GiB内存的共享服务器。服务进程限制在CPU0–7，JMeter在8–11；这是进程亲和性，不是独占资源。JMeter与服务在同一台真实服务器，测量链路为 JMeter → 回环Nginx → Java，未包含公网RTT。

- Java8u504、Spring Boot2.7.4，单实例，512MiB G1堆，ActiveProcessorCount=8。
- MySQL8.0.28，buffer pool512MiB，flush_log_at_trx_commit=1、sync_binlog=1。
- Redis6.2.16，AOF开启/everysec；RabbitMQ3.9.13，持久经典队列和消息，消费者2、prefetch10，4调度器。
- Nginx1.18.0，2workers、worker_connections2048，上游keepalive64。Hikari max10、Tomcat max200。
- USER限流保持开启（5次/10秒）；每次迭代使用独立测试用户，不混入重复下单。支付超时900秒、定时关单和300秒异步恢复期限保持开启。
- 所有项目服务监听回环地址；管理凭证在服务器内部生成并保留，没有导出。服务数据最终放到 `/var/tmp/hmdp-test-runtime/runtime`（Intel SSD，/dev/sdb3），保留原HDD目录备份。该测试目录可能受系统临时目录清理策略影响，长期部署应改正式SSD持久目录。

## 存储优化的实际证据

首次服务数据在 `/home` 的旋转磁盘阵列（MR9361-8i、/dev/sda1）。10线程测试时 MySQL平均commit约19.86ms，消费者落库明显慢；将整个项目runtime移到SSD后，后续实例采样平均commit约0.86ms。这些SQL统计是实例累计值，不等同于每请求精确分解。

| 10线程对照 | HDD目录 | SSD目录 |
|---|---:|---:|
| 配置时长 / ramp | 10秒 / 1秒 | 10秒 / 1秒 |
| 到达速率设置 | 900请求/秒 | 900请求/秒 |
| 实际请求数 | 1405 | 8873 |
| 全程受理TPS | 141.605 | 894.095 |
| P95 | 202ms | 13ms |
| HTTP/业务错误 | 0 | 0 |
| JMeter结束后的核对等待 | 24.840秒 | 14.827秒 |
| 每笔订单、库存、资格、队列核对 | 全通过 | 全通过 |

两轮都未耗尽数据；SSD轮增加了预备数据量以保持同样10秒负载。核心持久化、业务事务、ACK、消费者数均未放宽。该对照是定位存储瓶颈的短测试，不将它当500线程结果或极限吞吐量。见 [HDD](../jmeter/results/server-check/summary.json)、[SSD](../jmeter/results/server-ssd-check/summary.json)。

## 500线程正式复测

2026-09-15 09:57:18～09:58:19（Asia/Shanghai），JMeter5.6.3/Java8，HEAP=-Xms512m -Xmx1g；500线程、ramp10秒、总时长60秒、目标到达速率900请求/秒、CSV10万行与库存10万，独立券12。定时器并非精确速率发生器，最终以JTL实际样本计算。

TPS是**入口受理吞吐量**，包含publisher confirm等待，不等同于消费者即时提交TPS。所有HTTP/业务失败均纳入统计，不能只凭HTTP200判断成功。稳态取首样本时间后12秒至58秒，避开爬升和停线程边缘；全程指标另行保留。

| 指标 | 全程 | 46秒稳态窗口 |
|---|---:|---:|
| 请求数 | 59,425 | 45,981 |
| 成功受理TPS | 984.656 | 999.587 |
| P95 | 17ms | 18ms |
| P99 | 50ms | 44ms |
| HTTP/业务错误 | 0 / 0% | 0 / 0% |
| 实际线程数 | 包含爬升/结束 | min=max=500 |

**本场达到设定的服务器入口性能目标**，不是无限持续负载或极限容量认证。CSV未耗尽；所有59,425个返回订单号和用户ID与MySQL逐笔相符，全部PENDING，delivery全部CREATED。Redis/MySQL剩余库存均40,575，资格Set（扣除哨兵）与owner均59,425。主队列/DLQ排空，parking无新增，8项核对全部通过。

本轮采样主队列最高ready+unacked为 **30,018**，施压结束后 **73.313秒** 完成核对。此时全部落库，但消费瞬时能力并非约1000单/秒；系统以队列吸收这一分钟的突发流量。不能由此宣称长期稳定1000TPS落库，也不能忽略队列会在持续更久时增长。支付超时与异步恢复期限保持默认，本轮在它们触发前完成核对。

证据：[summary.json](../jmeter/results/server-500-ssd/summary.json)、[实际参数](../jmeter/results/server-500-ssd/parameters.json)、[环境](../jmeter/results/server-500-ssd/environment.json)、[逐秒数据](../jmeter/results/server-500-ssd/per-second.csv)、[队列曲线](../jmeter/results/server-500-ssd/queues.json)、原始JTL（本地/服务器归档；公开仓库提供[汇总结果](../jmeter/results/server-500-ssd/summary.json)）。JTL包含每请求时间与测试userId/orderId，没有登录token。HTML报告在服务器 `~/hmdp-m7/jmeter/results/server-500-ssd/report/index.html`，不将截图作为替代证据。

## 工程交付与验证

- `deploy/Dockerfile`、`compose.server.yml`、`nginx.conf`：容器模板和代理复用/超时配置；Compose尚未实机运行。
- `portable-configure.py`、`portable-env.sh`、`portable-start.sh`、`portable-stop.sh`：已在实际服务器执行的独立用户态部署，私有运行库、随机凭证、端口检查、真实AMQP就绪与优雅停启。镜像方式与用户态方式择一使用。
- `application.yml`：Hikari/RabbitMQ参数可覆盖，默认仍10/2/10，没有盲目放大线程池。
- `jmeter/seckill-500.jmx`、`prepare.py`、`run.py`、`reconcile.py`：独立数据、滑动窗口限流保持开启、业务断言、真实施压、指标与异步一致性核对。具体执行见 [JMeter说明](../jmeter/README.md)。
- `mvn -q -DskipTests package` 成功；Python/shell/JMX XML/Compose YAML检查通过；Nginx配置检查通过，服务器代理查询与真实秒杀链路成功；补齐用户态MIME映射后，首页/CSS/JS/API均200且Content-Type正确。
- 本地负向断言验证：5次HTTP200业务拒绝与1次429全部正确标为失败；不会把200当成功。
- 服务器初次遇到RabbitMQ AMQP/分布式端口重叠，已分离到25672/25674并通过真实队列消费者检查。此类启动失败被前置检查拦住，没有计入正式性能结果。

## 使用与边界

服务器服务保持运行。按 [部署说明](../deploy/README.md) 通过SSH隧道访问前端，凭证留在服务器。当前SSD目录位于/var/tmp，适用于此次测试；长期托管需迁往正式持久目录并确定备份策略。课程后台演示接口、模拟支付、单实例Caffeine一致性等既有范围没有被扩展为正式生产系统保证。

本地WSL早期无速率限制测试的数据耗尽、消费积压、恢复取消及后续结算都保留在 [本地历史记录](m7-local-verification.md)。它与服务器的硬件、存储、负载模型不同，不用于计算“从本地到服务器提升百分比”。服务器达标不会删除早期失败证据。

后续最值得做：针对业务预期持续时长做更长时间的队列增长/落库能力验证，或在正式SSD持久目录完成长期运行；这些不包含在本次60秒入口目标的结论中。
