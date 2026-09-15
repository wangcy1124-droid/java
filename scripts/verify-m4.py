#!/usr/bin/env python3
"""M4 商户缓存 HTTP 验证；仅在专用开发库运行，创建并更新一个商户夹具。"""
import json
import runpy
import time
from pathlib import Path

helpers = runpy.run_path(str(Path(__file__).with_name('verify-m0.py')))
request, redis, sql, eventually = (helpers[k] for k in ('request', 'redis', 'sql', 'eventually'))


def main():
    name = 'M4-http-%d' % (time.time_ns() // 1000)
    request('/shop', 'POST', {'name': name, 'typeId': 1, 'images': '/imgs/test.jpg',
                             'address': 'M4 HTTP fixture', 'x': 120, 'y': 30,
                             'sold': 0, 'comments': 0, 'score': 50})
    shop_id = int(sql("SELECT id FROM tb_shop WHERE name='%s'" % name))
    path, key = '/shop/%d' % shop_id, 'cache:shop:%d' % shop_id
    first = request(path)
    assert first['name'] == name and first['id'] == shop_id
    assert request(path)['name'] == name
    ttl = int(redis('TTL', key))
    assert 0 < ttl <= 2100, ttl
    assert json.loads(redis('GET', key))['id'] == shop_id
    request('/shop', 'PUT', {'id': shop_id, 'name': name + '-updated'})
    assert redis('EXISTS', key) == '0', '更新返回后 L2 应失效'
    assert request(path)['name'] == name + '-updated', '更新返回后不能命中旧 L1'
    missing = 899999997
    request('/shop/%d' % missing, success=False)
    request('/shop/%d' % missing, success=False)
    assert redis('GET', 'cache:shop:%d' % missing) == ''
    assert 0 < int(redis('TTL', 'cache:shop:%d' % missing)) <= 120
    # 默认热点 shop=1；仅查询和检查封装，不修改课程商户数据。
    assert request('/shop/1')['id'] == 1
    eventually(lambda: 'expireTime' in json.loads(redis('GET', 'cache:shop:1')))
    hot = json.loads(redis('GET', 'cache:shop:1'))
    assert hot['data']['id'] == 1 and hot['expireTime'] > 0
    assert 0 < int(redis('TTL', 'cache:shop:1')) <= 600
    print('PASS 商户详情冷加载/重复查询、更新后 L1/L2 失效、空值短 TTL')
    print('PASS 默认热点商户逻辑过期封装及物理 TTL，普通商户保持兼容 JSON')
    print('EVIDENCE shopId=%d normalTTL=%d updatedName=%s hotspot=1' % (shop_id, ttl, name + '-updated'))


if __name__ == '__main__':
    main()
