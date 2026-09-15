#!/usr/bin/env python3
"""M6 打包应用社交 HTTP 验证；专用开发库，创建三个用户和一篇笔记。"""
import runpy
import time
from pathlib import Path

h = runpy.run_path(str(Path(__file__).with_name('verify-m0.py')))
request, redis, sql = (h[k] for k in ('request', 'redis', 'sql'))


def main():
    tokens, users = [], []
    stamp = int(time.time() * 1000) % 100000000
    for i in range(3):
        phone = '132%08d' % ((stamp + i) % 100000000)
        request('/user/code?phone=' + phone, 'POST')
        token = request('/user/login', 'POST', {'phone': phone, 'code': redis('GET', 'login:code:' + phone)})
        tokens.append(token)
        users.append(request('/user/me', token=token)['id'])
    a, b, author = users
    for token in tokens[:2]:
        request('/follow/%d/true' % author, 'PUT', token=token)
        request('/follow/%d/true' % author, 'PUT', token=token)
    assert sql('SELECT COUNT(*) FROM tb_follow WHERE follow_user_id=%d' % author) == '2'
    assert request('/follow/or/not/%d' % author, token=tokens[0]) is True
    assert [u['id'] for u in request('/follow/common/%d' % b, token=tokens[0])] == [author]
    redis('DEL', 'follows:%d' % a, 'follows:%d' % b)
    assert [u['id'] for u in request('/follow/common/%d' % b, token=tokens[0])] == [author]
    request('/follow/%d/false' % author, 'PUT', token=tokens[1])
    request('/follow/%d/false' % author, 'PUT', token=tokens[1])
    assert request('/follow/common/%d' % b, token=tokens[0]) == []
    request('/follow/%d/true' % a, 'PUT', token=tokens[0], success=False)
    print('PASS 关注/取关幂等、共同关注、丢失 Set 后重建、拒绝自关注')
    blog = request('/blog', 'POST', {'shopId': 1, 'title': 'M6 HTTP fixture', 'images': '/imgs/blogs/blog1.jpg',
                                    'content': 'M6 social verification', 'liked': 99}, token=tokens[2])
    assert sql('SELECT liked FROM tb_blog WHERE id=%d' % blog) == '0'
    for _ in range(2):
        request('/blog/like/%d/true' % blog, 'PUT', token=tokens[0])
    time.sleep(.02)
    request('/blog/like/%d/true' % blog, 'PUT', token=tokens[1])
    assert sql('SELECT liked FROM tb_blog WHERE id=%d' % blog) == '2'
    assert sql('SELECT COUNT(*) FROM tb_blog_like WHERE blog_id=%d' % blog) == '2'
    assert [u['id'] for u in request('/blog/likes/%d' % blog, token=tokens[0])] == [a, b]
    redis('DEL', 'blog:liked:%d' % blog)
    assert request('/blog/%d' % blog, token=tokens[0])['isLike'] is True
    assert [u['id'] for u in request('/blog/likes/%d' % blog, token=tokens[0])] == [a, b]
    for _ in range(2):
        request('/blog/like/%d/false' % blog, 'PUT', token=tokens[0])
    # 原课程 toggle 路径仍兼容：B 的一次 toggle 取消其点赞。
    request('/blog/like/%d' % blog, 'PUT', token=tokens[1])
    assert sql('SELECT liked FROM tb_blog WHERE id=%d' % blog) == '0'
    assert sql('SELECT COUNT(*) FROM tb_blog_like WHERE blog_id=%d' % blog) == '0'
    assert request('/blog/likes/%d' % blog, token=tokens[0]) == []
    assert request('/blog/%d' % blog, token=tokens[0])['isLike'] is False
    request('/blog/like/899999999/true', 'PUT', token=tokens[0], success=False)
    feed = request('/blog/of/follow?lastId=%d&offset=0' % (int(time.time()*1000)+1), token=tokens[0])
    assert any(item['id'] == blog for item in feed['list'])
    print('PASS 点赞/取消幂等、ZSet 时间顺序、丢失 ZSet 后重建、旧 toggle 兼容、关注动态')
    print('EVIDENCE blogId=%s users=%s,%s author=%s liked=0 relations=0 common=[]' % (blog, a, b, author))


if __name__ == '__main__':
    main()
