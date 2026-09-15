# Source from repository root; server-specific user-space deployment, loopback ports only.
export HMDP_ROOT="$(pwd)"
export JAVA_HOME="$HMDP_ROOT/.tools/jdk8u504-b01"
export PATH="$JAVA_HOME/bin:$HMDP_ROOT/.tools/sysroot/usr/lib/erlang/bin:$HMDP_ROOT/.tools/sysroot/usr/bin:$HMDP_ROOT/.tools/redis-6.2.16/src:$PATH"
export TZ=Asia/Shanghai
export MYSQL_HOST=127.0.0.1 MYSQL_PORT=23306 MYSQL_USER=root MYSQL_PASSWORD='' MYSQL_DATABASE=hmdp
export MYSQL_URL='jdbc:mysql://127.0.0.1:23306/hmdp?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=utf8'
export REDIS_HOST=127.0.0.1 REDIS_PORT=26379 REDIS_DATABASE=0
export RABBITMQ_HOST=127.0.0.1 RABBITMQ_PORT=25672 RABBITMQ_USER=hmdp_m7
export RABBITMQ_MANAGEMENT_URL=http://127.0.0.1:25673
export SERVER_ADDRESS=127.0.0.1
export RABBITMQ_DIST_PORT=25674
export SERVER_PORT=28081 BASE_URL=http://127.0.0.1:28080/api UPLOAD_DIR="$HMDP_ROOT/data/imgs"
export RABBITMQ_CONFIG_FILE="$HMDP_ROOT/runtime/rabbitmq.conf"
export RABBITMQ_MNESIA_BASE="$HMDP_ROOT/runtime/rabbitmq-data"
export RABBITMQ_LOG_BASE="$HMDP_ROOT/runtime/rabbitmq-logs"
export RABBITMQ_ENABLED_PLUGINS_FILE="$HMDP_ROOT/runtime/rabbitmq_enabled_plugins"
export RABBITMQ_NODENAME=hmdp_m7@localhost
export RABBITMQ_ERLANG_COOKIE="$(cat "$HMDP_ROOT/runtime/erlang.cookie")"
export RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS='+S 4:4 -kernel inet_dist_use_interface {127,0,0,1}'
export ERL_CRASH_DUMP="$HMDP_ROOT/runtime/erl_crash.dump"
export ERL_EPMD_ADDRESS=127.0.0.1 ERL_EPMD_PORT=24369
export HEAP='-Xms512m -Xmx1g'
source "$HMDP_ROOT/runtime/private.env"
