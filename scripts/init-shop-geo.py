#!/usr/bin/env python3
"""从 MySQL 初始化课程的商户 GEO 索引；沿用 verify-m0.py 的连接环境变量。"""
import runpy
from pathlib import Path

helpers = runpy.run_path(str(Path(__file__).with_name('verify-m0.py')))
rows = helpers['sql']('SELECT id,type_id,x,y FROM tb_shop')
count = 0
for line in rows.splitlines():
    shop_id, type_id, x, y = line.split('\t')
    helpers['redis']('GEOADD', 'shop:geo:' + type_id, x, y, shop_id)
    count += 1
print('Initialized %d shop GEO entries' % count)
