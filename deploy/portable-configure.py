#!/usr/bin/env python3
"""Configure an extracted portable tool bundle under this project, without system-wide library changes."""
import os
import re
import secrets
import subprocess
from pathlib import Path

root=Path(__file__).resolve().parents[1]
tools=root/'.tools';runtime=root/'runtime';runtime.mkdir(exist_ok=True);runtime.chmod(0o700)
patchelf=tools/'patchelf/usr/bin/patchelf'
loader=tools/'server-libs/ld-linux-x86-64.so.2'
libpath=':'.join(str(tools/p) for p in ('server-libs','sysroot/lib/x86_64-linux-gnu','sysroot/usr/lib/x86_64-linux-gnu'))
# Patch only project-owned ELF executables. No LD_LIBRARY_PATH export to system shells/tools.
for folder in [tools/'sysroot',tools/'redis-6.2.16/src']:
 for f in folder.rglob('*'):
  if not f.is_file() or f.is_symlink():continue
  with f.open('rb') as stream:magic=stream.read(4)
  if magic!=b'\x7fELF':continue
  result=subprocess.run([str(patchelf),'--print-interpreter',str(f)],capture_output=True)
  if result.returncode==0:
   current_path=subprocess.check_output([str(patchelf),'--print-rpath',str(f)],text=True).strip()
   if result.stdout.decode().strip()!=str(loader) or current_path!=libpath:
    subprocess.run([str(patchelf),'--set-interpreter',str(loader),'--force-rpath','--set-rpath',libpath,str(f)],check=True)
# Course portable Erlang scripts contain the extraction path, not a relocatable prefix.
for folder in [tools/'sysroot/usr/lib/erlang/bin',tools/'sysroot/usr/lib/erlang/erts-12.2.1/bin',tools/'sysroot/usr/bin']:
 for f in folder.iterdir():
  if not f.is_file() or f.is_symlink():continue
  if f.open('rb').read(2)!=b'#!':continue
  s=f.read_text()
  s=re.sub(r'/[^\s\"\']*/\.tools/sysroot', lambda _: str(tools/'sysroot'), s)
  if f.name=='erl':s=s.replace('ROOTDIR=/usr/lib/erlang','ROOTDIR='+str(tools/'sysroot/usr/lib/erlang'))
  f.write_text(s)
# Private, generated per deployment. Never print or package these runtime files.
cookie=runtime/'erlang.cookie'
if not cookie.exists(): cookie.write_text(secrets.token_hex(24));cookie.chmod(0o600)
credentials=runtime/'private.env'
if not credentials.exists():
 credentials.write_text(''.join('export '+key+'='+secrets.token_hex(24)+'\n' for key in ['MYSQL_PASSWORD','REDIS_PASSWORD','RABBITMQ_PASSWORD']))
 credentials.chmod(0o600)
passwords=dict(line[len('export '):].split('=',1) for line in credentials.read_text().splitlines())
conf=runtime/'rabbitmq.conf'
conf.write_text('listeners.tcp.1 = 127.0.0.1:25672\nmanagement.tcp.ip = 127.0.0.1\nmanagement.tcp.port = 25673\nvm_memory_high_watermark.absolute = 1GB\ndefault_user = hmdp_m7\ndefault_pass = '+passwords['RABBITMQ_PASSWORD']+'\n')
conf.chmod(0o600)
redisconf=runtime/'redis.conf'
redisconf.write_text('bind 127.0.0.1\nport 26379\ndir '+str(runtime)+'\nappendonly yes\nappendfilename m7.aof\nrequirepass '+passwords['REDIS_PASSWORD']+'\n')
redisconf.chmod(0o600)
init=runtime/'mysql-init.sql'
init.write_text("ALTER USER 'root'@'localhost' IDENTIFIED BY '"+passwords['MYSQL_PASSWORD']+"';\nCREATE DATABASE IF NOT EXISTS hmdp CHARACTER SET utf8mb4;\n")
init.chmod(0o600)
(runtime/'rabbitmq_enabled_plugins').write_text('[rabbitmq_management].\n')
nginx=(root/'deploy/nginx.conf').read_text().replace('${NGINX_UPSTREAM}','127.0.0.1:28081').replace('listen 80;','listen 127.0.0.1:28080;').replace('/usr/share/nginx/html',str(root/'frontend')).replace('/srv/uploads',str(root/'data/imgs'))
ng=runtime/'nginx';ng.mkdir(exist_ok=True)
(ng/'nginx.conf').write_text('worker_processes 2;\npid '+str(ng/'nginx.pid')+';\nerror_log '+str(ng/'error.log')+';\nevents { worker_connections 2048; }\nhttp {\ninclude '+str(root/'deploy/mime.types')+';\ndefault_type application/octet-stream;\naccess_log '+str(ng/'access.log')+';\n'+''.join(kind+'_temp_path '+str(ng/(kind+'_temp'))+';\n' for kind in ['client_body','proxy','fastcgi','uwsgi','scgi'])+nginx+'\n}\n')
print('Configured project-owned portable executables and service files under',root)
