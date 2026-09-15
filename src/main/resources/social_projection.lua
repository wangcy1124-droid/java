-- kind=zset/set; mode=replace/change; TTL秒。成员0为完整空集合标记，不返回给用户。
local kind = ARGV[1]
if ARGV[2] == 'replace' then
    redis.call('DEL', KEYS[1])
    if kind == 'zset' then
        redis.call('ZADD', KEYS[1], -1, '0')
        for i=4,#ARGV,2 do redis.call('ZADD', KEYS[1], ARGV[i+1], ARGV[i]) end
    else
        redis.call('SADD', KEYS[1], '0')
        for i=4,#ARGV do redis.call('SADD', KEYS[1], ARGV[i]) end
    end
else
    if redis.call('TYPE', KEYS[1]).ok ~= kind then return -1 end
    if redis.call('TTL', KEYS[1]) < 0 then return -1 end
    if kind == 'zset' then
        if not redis.call('ZSCORE', KEYS[1], '0') then return -1 end
        if ARGV[4] == '1' then redis.call('ZADD', KEYS[1], ARGV[6], ARGV[5])
        else redis.call('ZREM', KEYS[1], ARGV[5]) end
    else
        if redis.call('SISMEMBER', KEYS[1], '0') == 0 then return -1 end
        if ARGV[4] == '1' then redis.call('SADD', KEYS[1], ARGV[5])
        else redis.call('SREM', KEYS[1], ARGV[5]) end
    end
end
-- 增量写不延长整份快照寿命，删除失败后也不会因持续互动无限保留旧数据。
if ARGV[2] == 'replace' then redis.call('EXPIRE', KEYS[1], ARGV[3]) end
return 1
