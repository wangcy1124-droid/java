#!/usr/bin/env python3
"""Create a new isolated voucher and one persistent user/session per CSV row. Never resets stock."""
import argparse
import csv
import json
import os
import runpy
import socket
import time
import uuid
from datetime import datetime, timedelta
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
h = runpy.run_path(str(ROOT / 'scripts/verify-m0.py'))
sql, redis, request = (h[k] for k in ('sql', 'redis', 'request'))


def pipeline(commands):
    # Bounded RESP pipeline; no additional Python dependency or CLI process per user.
    def encode(args):
        parts = [str(x).encode() for x in args]
        return b'*%d\r\n' % len(parts) + b''.join(b'$%d\r\n' % len(p) + p + b'\r\n' for p in parts)
    with socket.create_connection((os.getenv('REDIS_HOST', '127.0.0.1'), int(os.getenv('REDIS_PORT', 6379))), 30) as s:
        f = s.makefile('rb')
        prefix = []
        if os.getenv('REDIS_PASSWORD'):
            prefix.append(['AUTH', os.environ['REDIS_PASSWORD']])
        prefix.append(['SELECT', os.getenv('REDIS_DATABASE', '0')])
        for group in (prefix, commands):
            s.sendall(b''.join(encode(c) for c in group))
            for _ in group:
                reply = f.readline()
                if not reply or reply[:1] not in (b'+', b':'):
                    raise RuntimeError('Redis fixture write failed: ' + repr(reply))


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--rows', type=int, default=100000)
    p.add_argument('--out', required=True, type=Path)
    args = p.parse_args()
    if args.rows < 1 or args.rows > 1000000:
        p.error('rows must be 1..1000000')
    args.out.mkdir(parents=True, exist_ok=False)
    os.chmod(args.out, 0o700)
    tag = 'm7_' + uuid.uuid4().hex[:16]
    now = datetime.now()
    voucher = request('/voucher/seckill', 'POST', dict(shopId=1, title=tag, subTitle='JMeter fixture',
        rules='isolated performance data', payValue=100, actualValue=200, type=1, status=1, stock=args.rows,
        beginTime=(now-timedelta(minutes=1)).isoformat(timespec='seconds'),
        endTime=(now+timedelta(hours=2)).isoformat(timespec='seconds')))
    assert redis('GET', 'seckill:stock:' + str(voucher)) == str(args.rows)
    # Dedicated synthetic phone namespace, monotonically allocated from existing fixture rows.
    start = int(sql("SELECT COALESCE(MAX(CAST(phone AS UNSIGNED)),19900000000) FROM tb_user WHERE phone LIKE '199%'") or 19900000000)
    assert start + args.rows <= 19999999999, 'fixture phone namespace exhausted'
    manifest = dict(tag=tag, voucher_id=voucher, stock=args.rows, rows=args.rows,
                    created_at=datetime.now().astimezone().isoformat(), base_url=h['BASE'], session_ttl_seconds=7200)
    (args.out/'manifest.json').write_text(json.dumps(manifest, indent=2)+'\n')
    with (args.out/'users.csv').open('w', newline='') as f:
        writer = csv.writer(f)
        writer.writerow(['token', 'userId', 'voucherId'])
        for offset in range(0, args.rows, 500):
            n = min(500, args.rows-offset)
            values = ','.join("('%d','%s')" % (start+offset+i+1, tag) for i in range(n))
            sql('INSERT INTO tb_user(phone,nick_name) VALUES '+values)
            ids = sql("SELECT id FROM tb_user WHERE phone BETWEEN '%d' AND '%d' AND nick_name='%s' ORDER BY id" %
                      (start+offset+1, start+offset+n, tag)).splitlines()
            assert len(ids) == n
            commands = []
            for user in ids:
                token = uuid.uuid4().hex
                key = 'login:token:'+token
                commands.extend([['HSET',key,'id',user,'nickName',tag,'icon',''], ['EXPIRE',key,7200]])
                writer.writerow([token,user,voucher])
            pipeline(commands)
    os.chmod(args.out/'users.csv', 0o600)
    with (args.out/'users.csv').open() as f:
        first = next(csv.DictReader(f))
    assert str(request('/user/me', token=first['token'])['id']) == first['userId']
    request('/shop/1')
    print(json.dumps(manifest, ensure_ascii=False))


if __name__ == '__main__':
    main()
