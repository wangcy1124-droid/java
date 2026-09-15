#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source deploy/portable-env.sh
mkdir -p runtime/mysql data/imgs
# Dedicated project listeners. Refuse a duplicate launch instead of touching another process.
python3 - <<'PY'
import socket
for port in [23306,26379,25672,25673,25674,28080,28081]:
 s=socket.socket();s.settimeout(.5)
 if s.connect_ex(('127.0.0.1',port))==0: raise SystemExit('Port already in use: '+str(port))
 s.close()
PY
if [[ ! -d runtime/mysql/mysql ]]; then
 .tools/sysroot/usr/sbin/mysqld --no-defaults --initialize-insecure --basedir="$HMDP_ROOT/.tools/sysroot/usr" --datadir="$HMDP_ROOT/runtime/mysql" > runtime/mysql-initialize.log 2>&1
fi
nohup taskset -c "${SERVICE_CPUS:-0-7}" .tools/sysroot/usr/sbin/mysqld --no-defaults \
 --basedir="$HMDP_ROOT/.tools/sysroot/usr" --datadir="$HMDP_ROOT/runtime/mysql" \
 --bind-address=127.0.0.1 --port=23306 --socket="$HMDP_ROOT/runtime/mysql.sock" \
 --mysqlx=0 --secure-file-priv="$HMDP_ROOT/runtime" --pid-file="$HMDP_ROOT/runtime/mysql.pid" \
 --init-file="$HMDP_ROOT/runtime/mysql-init.sql" --innodb-buffer-pool-size=536870912 \
 --log-error="$HMDP_ROOT/runtime/mysql.log" > runtime/mysql-console.log 2>&1 < /dev/null &
nohup taskset -c "${SERVICE_CPUS:-0-7}" redis-server "$HMDP_ROOT/runtime/redis.conf" > runtime/redis.log 2>&1 < /dev/null &
echo $! > runtime/redis.pid
nohup taskset -c "${SERVICE_CPUS:-0-7}" .tools/sysroot/usr/lib/rabbitmq/lib/rabbitmq_server-3.9.13/sbin/rabbitmq-server > runtime/rabbitmq-console.log 2>&1 < /dev/null &
echo $! > runtime/rabbitmq.pid
python3 - <<'PY'
import time,runpy
h=runpy.run_path('scripts/verify-m0.py')
for attempt in range(60):
 try:
  assert h['sql']('SELECT 1')=='1'; assert h['redis']('PING')=='PONG';break
 except Exception:
  time.sleep(1)
else: raise SystemExit('MySQL/Redis startup failed; inspect runtime logs')
PY
if [[ ! -f runtime/schema-ready ]]; then
 export MYSQL_PWD="$MYSQL_PASSWORD"
 for migration in sql/00_base.sql sql/01_order_delivery.sql sql/02_order_lifecycle.sql sql/03_social.sql; do
  mysql --no-defaults --protocol=TCP -h 127.0.0.1 -P 23306 -u root hmdp < "$migration"
 done
 unset MYSQL_PWD
 touch runtime/schema-ready
fi
# RabbitMQ may take longer on first boot; do not start Java until the authenticated management API responds.
python3 - <<'PY'
import base64,json,os,time,urllib.request
urllib.request.install_opener(urllib.request.build_opener(urllib.request.ProxyHandler({})))
req=urllib.request.Request(os.environ['RABBITMQ_MANAGEMENT_URL']+'/api/overview',headers={'Authorization':'Basic '+base64.b64encode((os.environ['RABBITMQ_USER']+':'+os.environ['RABBITMQ_PASSWORD']).encode()).decode()})
for attempt in range(90):
 try:
  with urllib.request.urlopen(req,timeout=2) as r: overview=json.load(r)
  assert any(x['protocol']=='amqp' and x['port']==25672 for x in overview['listeners'])
  break
 except Exception:time.sleep(1)
else:raise SystemExit('RabbitMQ startup failed; inspect runtime logs')
PY
nohup taskset -c "${SERVICE_CPUS:-0-7}" java -XX:ActiveProcessorCount=8 -Xms512m -Xmx512m -XX:+UseG1GC \
 -Xloggc:runtime/app-gc.log -XX:+PrintGCDetails -XX:+PrintGCDateStamps \
 -jar hmdp-1.0-SNAPSHOT.jar > runtime/app.log 2>&1 < /dev/null &
echo $! > runtime/app.pid
.tools/sysroot/usr/sbin/nginx -p "$HMDP_ROOT/runtime/nginx/" -c "$HMDP_ROOT/runtime/nginx/nginx.conf" -t
nohup taskset -c "${SERVICE_CPUS:-0-7}" .tools/sysroot/usr/sbin/nginx -p "$HMDP_ROOT/runtime/nginx/" -c "$HMDP_ROOT/runtime/nginx/nginx.conf" -g 'daemon off;' > runtime/nginx-console.log 2>&1 < /dev/null &
python3 - <<'PY'
import runpy,time
h=runpy.run_path('scripts/verify-m0.py')
for attempt in range(60):
 try:
  assert h['request']('/shop/1')['id']==1;print('READY Nginx -> application -> Redis/MySQL');break
 except Exception:time.sleep(1)
else:raise SystemExit('Application startup failed; inspect runtime/app.log')
PY
