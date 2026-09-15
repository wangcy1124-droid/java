-- KEYS: stock, users, owners, reservation, pending
-- ARGV: userId, token, orderId (empty = must still be unbound), keepQualification, voucherId
-- A reaper may hold an unbound snapshot while the consumer already completed.
if redis.call('EXISTS', KEYS[4]) == 0 then return 0 end
if redis.call('HGET', KEYS[4], 'userId') ~= ARGV[1]
    or redis.call('HGET', KEYS[4], 'voucherId') ~= ARGV[5] then return -1 end
local owner = redis.call('HGET', KEYS[3], ARGV[1])
if not owner then return -1 end
local types = {'string', 'set', 'hash', 'hash', 'zset'}
for i = 1, #types do
    local actual = redis.call('TYPE', KEYS[i]).ok
    if actual ~= types[i] then return -1 end
end
local bound = redis.call('HGET', KEYS[4], 'orderId') or ''
if bound ~= ARGV[3] then return 0 end
if redis.call('HGET', KEYS[3], ARGV[1]) == ARGV[2] then
    if not tonumber(redis.call('GET', KEYS[1])) then return -1 end
    redis.call('INCR', KEYS[1])
    if ARGV[4] ~= '1' then redis.call('SREM', KEYS[2], ARGV[1]) end
    redis.call('HDEL', KEYS[3], ARGV[1])
end
redis.call('DEL', KEYS[4])
redis.call('ZREM', KEYS[5], ARGV[2])
return 1
