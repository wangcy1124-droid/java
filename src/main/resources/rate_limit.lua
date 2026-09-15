-- KEYS[1]: 接口+维度桶；ARGV: limit, windowMillis, 唯一请求 token
local limit = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
if not limit or limit <= 0 or not window or window <= 0 then
    return redis.error_reply('invalid rate limit configuration')
end
local clock = redis.call('TIME')
local now = tonumber(clock[1]) * 1000 + math.floor(tonumber(clock[2]) / 1000)
-- 窗口为 (now-window, now]，时间来自 Redis，避免应用实例之间时钟偏移。
redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now - window)
if redis.call('ZCARD', KEYS[1]) >= limit then
    local oldest = redis.call('ZRANGE', KEYS[1], 0, 0, 'WITHSCORES')
    return math.max(1, tonumber(oldest[2]) + window - now)
end
redis.call('ZADD', KEYS[1], now, tostring(now) .. ':' .. ARGV[3])
redis.call('PEXPIRE', KEYS[1], window)
-- 被拒请求不占用名额，也不延长 key TTL。
return 0
