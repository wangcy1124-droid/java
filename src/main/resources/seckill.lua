-- KEYS: stock, users, meta, owners, reservation, pending; ARGV: userId, token, voucherId
-- This qualification script has no dependency on an order transport.
-- Lua errors do not roll back writes: validate key types before pre-deduction.
local types = {'string', 'set', 'hash', 'hash', 'hash', 'zset'}
for i = 1, #types do
    local actual = redis.call('TYPE', KEYS[i]).ok
    if actual ~= 'none' and actual ~= types[i] then return 3 end
end
local stock = tonumber(redis.call('GET', KEYS[1]))
local beginTime = tonumber(redis.call('HGET', KEYS[3], 'beginTime'))
local endTime = tonumber(redis.call('HGET', KEYS[3], 'endTime'))
if not stock or not beginTime or not endTime or redis.call('EXISTS', KEYS[2]) == 0 then return 3 end
local clock = redis.call('TIME')
local now = tonumber(clock[1]) * 1000 + math.floor(tonumber(clock[2]) / 1000)
if now < beginTime then return 4 end
if now >= endTime then return 5 end
if stock <= 0 then return 1 end
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then return 2 end
redis.call('DECR', KEYS[1])
redis.call('SADD', KEYS[2], ARGV[1])
redis.call('HSET', KEYS[4], ARGV[1], ARGV[2])
redis.call('HSET', KEYS[5], 'userId', ARGV[1], 'voucherId', ARGV[3], 'reservationId', ARGV[2])
redis.call('ZADD', KEYS[6], now, ARGV[2])
return 0
