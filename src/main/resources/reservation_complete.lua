if redis.call('HGET', KEYS[1], 'orderId') == ARGV[1] then
    redis.call('DEL', KEYS[1])
    redis.call('ZREM', KEYS[2], ARGV[2])
end
return 1
