#!/usr/bin/env python3
"""M5 打包应用真实限流验证：默认开启，每用户 10 秒 5 次；新增两用户、一张券。"""
import json
import time
import runpy
import urllib.request
import urllib.error
from pathlib import Path
from datetime import datetime, timedelta

h = runpy.run_path(str(Path(__file__).with_name('verify-m0.py')))
request, redis, sql, eventually, base = (h[k] for k in ('request', 'redis', 'sql', 'eventually', 'BASE'))


def buy(voucher, token):
    req = urllib.request.Request(base + '/voucher-order/seckill/' + str(voucher), method='POST',
                                 headers={'authorization': token})
    try:
        response = urllib.request.urlopen(req, timeout=10)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        return response.code, json.load(response), response.headers.get('Retry-After')


def main():
    tokens = []
    stamp = int(time.time() * 1000) % 100000000
    for i in range(2):
        phone = '134%08d' % ((stamp + i) % 100000000)
        request('/user/code?phone=' + phone, 'POST')
        tokens.append(request('/user/login', 'POST', {'phone': phone, 'code': redis('GET', 'login:code:' + phone)}))
    now = datetime.now()
    voucher = request('/voucher/seckill', 'POST', {
        'shopId': 1, 'title': 'M5 HTTP limit fixture', 'subTitle': 'development only', 'rules': 'M5',
        'payValue': 100, 'actualValue': 200, 'type': 1, 'status': 1, 'stock': 2,
        'beginTime': (now - timedelta(minutes=1)).isoformat(timespec='seconds'),
        'endTime': (now + timedelta(hours=1)).isoformat(timespec='seconds')})
    status, first, _ = buy(voucher, tokens[0])
    assert status == 200 and first['success'], first
    eventually(lambda: sql('SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=%d' % voucher) == '1')
    for _ in range(4):
        status, result, _ = buy(voucher, tokens[0])
        assert status == 200 and not result['success'] and result['errorMsg'] == '禁止重复下单', (status, result)
    status, result, retry = buy(voucher, tokens[0])
    assert status == 429 and result['errorMsg'] == '请求过于频繁', (status, result)
    assert 1 <= int(retry) <= 10
    assert redis('GET', 'seckill:stock:' + str(voucher)) == '1'
    assert sql('SELECT stock FROM tb_seckill_voucher WHERE voucher_id=%d' % voucher) == '1'
    print('PASS 同用户第 6 次秒杀 HTTP 429，Retry-After=%s；拒绝请求不预扣库存' % retry)
    status, second, _ = buy(voucher, tokens[1])
    assert status == 200 and second['success'], second
    eventually(lambda: sql('SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=%d' % voucher) == '2')
    assert redis('GET', 'seckill:stock:' + str(voucher)) == '0'
    assert sql('SELECT stock FROM tb_seckill_voucher WHERE voucher_id=%d' % voucher) == '0'
    print('PASS 另一用户独立限额，RabbitMQ 落库及双端库存正确')
    time.sleep(int(retry) + .1)
    status, result, _ = buy(voucher, tokens[0])
    assert status == 200 and not result['success'] and result['errorMsg'] == '库存不足', (status, result)
    print('PASS 窗口结束后恢复进入业务（券已售罄，返回业务库存不足而非 429）')
    print('EVIDENCE voucherId=%s orders=%s,%s MySQL stock=0 Redis stock=0' % (voucher, first['data'], second['data']))


if __name__ == '__main__':
    main()
