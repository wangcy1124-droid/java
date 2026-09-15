#!/usr/bin/env python3
"""M1 真实 Redis Lua 与 HTTP 并发验证，只针对专用开发环境。"""
import concurrent.futures
import json
import runpy
import time
import uuid
from pathlib import Path
from datetime import datetime, timedelta

h = runpy.run_path(str(Path(__file__).with_name('verify-m0.py')))
redis, sql, request, eventually = (h[k] for k in ['redis', 'sql', 'request', 'eventually'])
root = Path(__file__).resolve().parents[1]
qualification = (root / 'src/main/resources/seckill.lua').read_text()
preheat = (root / 'src/main/resources/seckill_preheat.lua').read_text()
prefix = 'test:m1:' + uuid.uuid4().hex
keys = [prefix + ':' + k for k in ['stock', 'users', 'meta', 'owners', 'reservation', 'pending']]
try:
    now = int(time.time() * 1000)
    args = [7, now - 60000, now + 60000, 1]
    token = uuid.uuid4().hex
    assert redis('EVAL', preheat, 3, *keys[:3], *args) == '1'
    assert redis('EVAL', qualification, 6, *keys, 101, token, 1) == '0'
    assert redis('EVAL', qualification, 6, *keys, 101, token, 1) == '2'
    assert redis('EVAL', preheat, 3, *keys[:3], *args) == '0'
    assert redis('GET', keys[0]) == '6', '重复预热不可重置库存'
    redis('DEL', keys[0])
    assert redis('EVAL', qualification, 6, *keys, 102, token, 1) == '3'
    assert redis('EVAL', preheat, 3, *keys[:3], *args) == '0'
    assert redis('EXISTS', keys[0]) == '0'
    redis('DEL', *keys)
    assert redis('EVAL', preheat, 3, *keys[:3], 7, now-60000, now+60000, 0) == '-1'
    assert redis('EVAL', preheat, 3, *keys[:3], *args) == '1'
    redis('SET', keys[3], 'wrong-type')
    assert redis('EVAL', qualification, 6, *keys, 102, token, 1) == '3'
    assert redis('GET', keys[0]) == '7'
    redis('DEL', keys[3], keys[1])
    assert redis('EVAL', qualification, 6, *keys, 101, token, 1) == '3'
    print('PASS 资格脚本、重复预热、库存/资格缺失、错误 key 类型无预扣')
finally:
    redis('DEL', *keys)

def coupon(stock, begin=-1, end=60):
    now = datetime.now()
    return request('/voucher/seckill', 'POST', {
        'shopId': 1, 'title': 'M1 concurrency verification', 'subTitle': 'development',
        'rules': 'M1', 'payValue': 100, 'actualValue': 200, 'type': 1, 'status': 1,
        'stock': stock, 'beginTime': (now + timedelta(minutes=begin)).isoformat(timespec='seconds'),
        'endTime': (now + timedelta(minutes=end)).isoformat(timespec='seconds')})

tokens = []
stamp = int(time.time()*1000) % 100000000
for i in range(40):
    phone = '138%08d' % ((stamp+i) % 100000000)
    request('/user/code?phone=' + phone, 'POST')
    tokens.append(request('/user/login', 'POST', {'phone': phone, 'code': redis('GET', 'login:code:' + phone)}))

def buy(voucher, token):
    import urllib.request
    req = urllib.request.Request(h['BASE'] + '/voucher-order/seckill/' + str(voucher),
                                 method='POST', headers={'authorization': token})
    with urllib.request.urlopen(req, timeout=10) as response:
        return json.load(response)

for voucher, message in [(coupon(2, 10, 60), '秒杀尚未开始'),
                         (coupon(2, -60, -1), '秒杀已结束'),
                         (999999999, '秒杀券不存在或尚未准备就绪')]:
    result = buy(voucher, tokens[0])
    assert not result['success'] and result['errorMsg'] == message, result
    if voucher != 999999999:
        assert redis('GET', 'seckill:stock:' + str(voucher)) == '2'
        assert sql('SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=%d' % voucher) == '0'
print('PASS 未开始/已结束/不存在券，拒绝后库存不变且无订单')

for same_user, stock, count in [(False, 20, 40), (True, 20, 40)]:
    voucher = coupon(stock)
    with concurrent.futures.ThreadPoolExecutor(max_workers=20) as pool:
        results = list(pool.map(lambda i: buy(voucher, tokens[0] if same_user else tokens[i]), range(count)))
    successful = [r for r in results if r['success']]
    expected = 1 if same_user else stock
    assert len(successful) == expected, results
    assert all(r['success'] or r.get('errorMsg') == ('禁止重复下单' if same_user else '库存不足') for r in results)
    eventually(lambda: sql('SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=%d' % voucher) == str(expected))
    assert redis('GET', 'seckill:stock:' + str(voucher)) == str(stock-expected)
    assert sql('SELECT stock FROM tb_seckill_voucher WHERE voucher_id=%d' % voucher) == str(stock-expected)
    assert redis('SCARD', 'seckill:order:' + str(voucher)) == str(expected+1)  # sentinel 0
    assert sql('SELECT COUNT(DISTINCT user_id) FROM tb_voucher_order WHERE voucher_id=%d' % voucher) == str(expected)
    print('PASS %s: requests=%d accepted=%d stock=%d voucherId=%d' %
          ('同用户并发' if same_user else '多用户并发', count, expected, stock-expected, voucher))

eventually(lambda: redis('ZCARD', 'seckill:pending') == '0')
print('PASS RabbitMQ 已完成消费，待处理占用=0；本次不是性能压测')
