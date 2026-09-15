#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source deploy/portable-env.sh
python3 - <<'PY'
import os,signal,time
from pathlib import Path
root=Path.cwd()
# Select only this user's Java process whose cwd and application file match this deployment.
for path in Path('/proc').iterdir():
 if not path.name.isdigit():continue
 try:
  args=(path/'cmdline').read_bytes().split(b'\0')
  if path.stat().st_uid!=os.getuid() or (path/'cwd').resolve()!=root:continue
  if b'-jar' not in args or b'hmdp-1.0-SNAPSHOT.jar' not in args:continue
  pid=int(path.name);os.kill(pid,signal.SIGTERM)
  for _ in range(60):
   if not path.exists():break
   time.sleep(.5)
  else:raise SystemExit('Java did not stop gracefully: '+str(pid))
 except (PermissionError,FileNotFoundError,ProcessLookupError):pass
PY
.tools/sysroot/usr/sbin/nginx -p "$HMDP_ROOT/runtime/nginx/" -c "$HMDP_ROOT/runtime/nginx/nginx.conf" -s quit
export RABBITMQ_CTL_ERL_ARGS='+S 2:2'
.tools/sysroot/usr/lib/rabbitmq/lib/rabbitmq_server-3.9.13/sbin/rabbitmqctl shutdown
REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli -h 127.0.0.1 -p 26379 SHUTDOWN
MYSQL_PWD="$MYSQL_PASSWORD" mysqladmin --no-defaults --protocol=TCP -h 127.0.0.1 -P 23306 -u root shutdown
