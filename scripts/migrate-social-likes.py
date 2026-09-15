#!/usr/bin/env python3
"""仅供停流从 M5 升级 M6：执行 03_social.sql 后，启动 M6 前导入旧 Redis 点赞身份。"""
import runpy
from pathlib import Path
from decimal import Decimal

h = runpy.run_path(str(Path(__file__).with_name('verify-m0.py')))
redis, sql = h['redis'], h['sql']


def main():
    if sql('SELECT COUNT(*) FROM tb_blog_like') != '0':
        raise SystemExit('点赞关系表非空：拒绝重复导入。此脚本仅用于停流升级，不用于在线修复。')
    blogs = dict(line.split('\t') for line in sql('SELECT id,liked FROM tb_blog').splitlines())
    users = set(sql('SELECT id FROM tb_user').splitlines())
    records = []
    for key in redis('--scan', '--pattern', 'blog:liked:*').splitlines():
        blog = key.removeprefix('blog:liked:')
        if not blog.isdigit() or blog not in blogs:
            raise SystemExit('未知笔记缓存，请先核对：' + key)
        values = redis('ZRANGE', key, 0, -1, 'WITHSCORES').splitlines()
        known = 0
        for i in range(0, len(values), 2):
            user, score = values[i:i+2]
            if user == '0': continue
            if not user.isdigit() or user not in users:
                raise SystemExit('未知点赞用户，请先核对：' + key + '/' + user)
            timestamp = Decimal(score)
            if timestamp < 0 or timestamp != int(timestamp) or timestamp > 9223372036854775807:
                raise SystemExit('点赞时间不合法：' + key)
            records.append('(%d,%d,%d)' % (int(blog), int(user), int(timestamp)))
            known += 1
        if known > int(blogs[blog]):
            raise SystemExit('Redis 点赞人数大于数据库计数，请先核对：' + key)
    if records:
        statements = ['START TRANSACTION']
        for start in range(0, len(records), 200):
            statements.append('INSERT INTO tb_blog_like(blog_id,user_id,liked_at) VALUES ' + ','.join(records[start:start+200]))
        statements.append('COMMIT')
        sql(';'.join(statements))
    print('Imported %d known Redis likes; existing blog totals preserved; no synthetic user relations.' % len(records))


if __name__ == '__main__':
    main()
