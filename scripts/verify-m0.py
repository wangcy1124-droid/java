#!/usr/bin/env python3
"""真实 HTTP + MySQL + Redis 的 M0 验证；只在专用开发数据库运行。
新增测试用户、券、订单、笔记；不清空数据库或 Redis。
"""
import json
import os
import subprocess
import time
import urllib.request
import urllib.error
from datetime import datetime, timedelta

# 开发验证直接连接目标地址，不经系统 HTTP 代理。
urllib.request.install_opener(urllib.request.build_opener(urllib.request.ProxyHandler({})))

BASE = os.getenv('BASE_URL', 'http://127.0.0.1:8081').rstrip('/')

def redis(*args):
    env = dict(os.environ)
    if env.get('REDIS_PASSWORD'):
        env['REDISCLI_AUTH'] = env['REDIS_PASSWORD']
    return subprocess.check_output([
        os.getenv('REDIS_CLI', 'redis-cli'), '--raw',
        '-h', os.getenv('REDIS_HOST', '127.0.0.1'),
        '-p', os.getenv('REDIS_PORT', '6379'),
        '-n', os.getenv('REDIS_DATABASE', '0'), *map(str, args)
    ], env=env, text=True).rstrip('\n')

def sql(query):
    env = dict(os.environ)
    env['MYSQL_PWD'] = env.get('MYSQL_PASSWORD', '')
    return subprocess.check_output([
        os.getenv('MYSQL_CLI', 'mysql'), '--no-defaults', '--protocol=TCP',
        '-h', os.getenv('MYSQL_HOST', '127.0.0.1'),
        '-P', os.getenv('MYSQL_PORT', '3306'),
        '-u', os.getenv('MYSQL_USER', 'root'), '-N', '-B',
        os.getenv('MYSQL_DATABASE', 'hmdp'), '-e', query
    ], env=env, text=True).strip()

def request(path, method='GET', data=None, token=None, success=True):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['authorization'] = token
    req = urllib.request.Request(BASE + path, method=method, headers=headers,
                                 data=None if data is None else json.dumps(data).encode())
    with urllib.request.urlopen(req, timeout=10) as response:
        result = json.load(response)
    assert result['success'] == success, (path, result)
    return result.get('data')

def eventually(check):
    for _ in range(100):
        if check():
            return
        time.sleep(.1)
    raise AssertionError('异步结果在 10 秒内未出现')

def main():
    assert request('/shop-type/list')
    shop = request('/shop/1')
    assert shop['id'] == 1
    assert request('/shop/1')['id'] == 1
    assert request('/shop/of/type?typeId=1&current=1')
    request('/shop/999999999', success=False)
    request('/shop/999999999', success=False)
    assert redis('GET', 'cache:shop:999999999') == ''
    assert int(redis('TTL', 'cache:shop:999999999')) > 0
    assert request('/blog/hot')
    assert request('/voucher/list/1')
    print('PASS 商户冷缓存/命中/空值、分类、热门博客、优惠券列表')

    tokens, users = [], []
    stamp = int(time.time() * 1000) % 100000000
    for i in range(2):
        phone = '139%08d' % ((stamp + i) % 100000000)
        request('/user/code?phone=' + phone, 'POST')
        code = redis('GET', 'login:code:' + phone)
        token = request('/user/login', 'POST', {'phone': phone, 'code': code})
        tokens.append(token)
        users.append(request('/user/me', token=token)['id'])
    t = tokens[0]
    request('/user/sign', 'POST', token=t)
    assert request('/user/sign/count', token=t) >= 1
    print('PASS 验证码登录、token、用户信息、签到')

    for token in tokens:
        request('/follow/1/true', 'PUT', token=token)
    assert request('/follow/or/not/1', token=t) is True
    assert any(u['id'] == 1 for u in request('/follow/common/' + str(users[1]), token=t))
    request('/follow/1/false', 'PUT', token=t)
    assert request('/follow/or/not/1', token=t) is False
    assert redis('SISMEMBER', 'follows:' + str(users[0]), 1) == '0'
    blog = request('/blog', 'POST', {'shopId': 1, 'title': 'M0 baseline verification',
                                  'images': '/imgs/blogs/blog1.jpg', 'content': 'M0 real integration'}, t)
    request('/blog/like/' + str(blog), 'PUT', token=t)
    assert any(u['id'] == users[0] for u in request('/blog/likes/' + str(blog), token=t))
    request('/blog/like/' + str(blog), 'PUT', token=t)
    assert sql('SELECT liked FROM tb_blog WHERE id=%d' % blog) == '0'
    print('PASS 发笔记、ZSet 点赞/取消、Set 关注/取关/共同关注')

    now = datetime.now()
    voucher = request('/voucher/seckill', 'POST', {
        'shopId': 1, 'title': 'M0 Stream test', 'subTitle': 'development only',
        'rules': 'M0 verification', 'payValue': 100, 'actualValue': 200,
        'type': 1, 'status': 1, 'stock': 2,
        'beginTime': (now - timedelta(minutes=1)).isoformat(timespec='seconds'),
        'endTime': (now + timedelta(hours=1)).isoformat(timespec='seconds')})
    assert redis('GET', 'seckill:stock:' + str(voucher)) == '2'
    order = request('/voucher-order/seckill/' + str(voucher), 'POST', token=t)
    eventually(lambda: sql('SELECT COUNT(*) FROM tb_voucher_order WHERE id=%d' % order) == '1')
    request('/voucher-order/seckill/' + str(voucher), 'POST', token=t, success=False)
    # M2 的消息重投与 ACK 验证由 OrderMessagingIT 覆盖。
    assert sql('SELECT stock FROM tb_seckill_voucher WHERE voucher_id=%d' % voucher) == '1'
    request('/voucher-order/seckill/' + str(voucher), 'POST', token=tokens[1])
    eventually(lambda: sql('SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=%d' % voucher) == '2')
    request('/voucher-order/seckill/' + str(voucher), 'POST', token=t, success=False)
    assert redis('GET', 'seckill:stock:' + str(voucher)) == '0'
    assert sql('SELECT stock FROM tb_seckill_voucher WHERE voucher_id=%d' % voucher) == '0'
    eventually(lambda: sql("SELECT COUNT(*) FROM tb_order_delivery WHERE voucher_id=%d AND state='CREATED'" % voucher) == '2')
    request('/voucher-order/seckill/999999999', 'POST', token=t, success=False)
    print('PASS RabbitMQ 预扣/落库、重复下单、库存不足、不存在券')
    print('EVIDENCE voucherId=%s orderId=%s MySQL orders=2 stock=0 Redis stock=0 CREATED=2' % (voucher, order))
    request('/user/logout', 'POST', token=t)
    try:
        request('/user/me', token=t)
        raise AssertionError('登出后 token 仍可使用')
    except urllib.error.HTTPError as error:
        assert error.code == 401
    print('PASS 登出后 token 失效')

if __name__ == '__main__':
    main()
