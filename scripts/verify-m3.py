#!/usr/bin/env python3
"""M3 打包应用的真实 HTTP/定时任务验证；专用开发库，支付超时配置为 10 秒。"""
import os
import runpy
import time
from pathlib import Path
from datetime import datetime, timedelta

helpers = runpy.run_path(str(Path(__file__).with_name('verify-m0.py')))
request, redis, sql = (helpers[name] for name in ('request', 'redis', 'sql'))


def wait_for(check, seconds=35):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if check():
            return
        time.sleep(.2)
    raise AssertionError('定时关单或异步落库未在期限内完成')


def main():
    tokens, users = [], []
    stamp = int(time.time() * 1000) % 100000000
    for i in range(2):
        phone = '135%08d' % ((stamp + i) % 100000000)
        request('/user/code?phone=' + phone, 'POST')
        token = request('/user/login', 'POST', {'phone': phone, 'code': redis('GET', 'login:code:' + phone)})
        tokens.append(token)
        users.append(request('/user/me', token=token)['id'])
    now = datetime.now()
    voucher = request('/voucher/seckill', 'POST', {
        'shopId': 1, 'title': 'M3 scheduled lifecycle verification', 'subTitle': 'development only',
        'rules': 'M3', 'payValue': 100, 'actualValue': 200, 'type': 1, 'status': 1, 'stock': 2,
        'beginTime': (now - timedelta(minutes=1)).isoformat(timespec='seconds'),
        'endTime': (now + timedelta(hours=1)).isoformat(timespec='seconds')})
    paid = request('/voucher-order/seckill/' + str(voucher), 'POST', token=tokens[0])
    closed = request('/voucher-order/seckill/' + str(voucher), 'POST', token=tokens[1])
    wait_for(lambda: sql('SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=%d' % voucher) == '2')
    order = request('/voucher-order/' + str(paid), token=tokens[0])
    assert order['status'] == 1 and order['version'] == 0, order
    duration = (datetime.fromisoformat(order['expireTime']) - datetime.fromisoformat(order['createTime'])).total_seconds()
    assert duration == 10, '请以 ORDER_PAYMENT_TIMEOUT_SECONDS=10、ORDER_CLOSE_DELAY_MS=1000 启动验证应用'
    request('/voucher-order/%s/simulate-pay' % paid, 'POST', token=tokens[0])
    request('/voucher-order/%s/simulate-pay' % paid, 'POST', token=tokens[0])
    request('/voucher-order/' + str(paid), token=tokens[1], success=False)
    wait_for(lambda: sql('SELECT completed FROM tb_order_stock_release WHERE order_id=%d' % closed) == '1')
    assert request('/voucher-order/' + str(paid), token=tokens[0])['status'] == 2
    assert request('/voucher-order/' + str(closed), token=tokens[1])['status'] == 4
    request('/voucher-order/%s/simulate-pay' % closed, 'POST', token=tokens[1], success=False)
    assert sql('SELECT stock FROM tb_seckill_voucher WHERE voucher_id=%d' % voucher) == '1'
    assert redis('GET', 'seckill:stock:' + str(voucher)) == '1'
    assert redis('SISMEMBER', 'seckill:order:' + str(voucher), users[1]) == '0'
    print('PASS RabbitMQ 创建待支付订单、仅本人查询/模拟支付、重复支付 version 不变')
    print('PASS @Scheduled 自动关单、已支付订单保持 PAID、已关闭订单拒绝支付、双端库存释放一次')
    repurchased = request('/voucher-order/seckill/' + str(voucher), 'POST', token=tokens[1])
    wait_for(lambda: sql('SELECT COUNT(*) FROM tb_voucher_order WHERE id=%d' % repurchased) == '1')
    request('/voucher-order/%s/simulate-pay' % repurchased, 'POST', token=tokens[1])
    assert sql('SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=%d AND status=2 AND version=1' % voucher) == '2'
    assert sql('SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=%d AND status=4 AND version=1' % voucher) == '1'
    assert sql('SELECT stock FROM tb_seckill_voucher WHERE voucher_id=%d' % voucher) == '0'
    assert redis('GET', 'seckill:stock:' + str(voucher)) == '0'
    print('PASS 关单后同用户同券重新购买，保留历史关闭订单')
    print('EVIDENCE voucherId=%s paid=%s closed=%s repurchased=%s; PAID=2 CLOSED=1; MySQL stock=0 Redis stock=0' %
          (voucher, paid, closed, repurchased))


if __name__ == '__main__':
    main()
