-- KEYS: stock, users, metadata. ARGV: stock, begin, end, newVoucher, buyers...
-- Never overwrite live stock or re-create a previously initialized reservation.
if redis.call('EXISTS', KEYS[3]) == 1 then return 0 end
local stockExists = redis.call('EXISTS', KEYS[1]) == 1
if not stockExists and ARGV[4] ~= '1' then
    return -1
end
if not stockExists then redis.call('SET', KEYS[1], ARGV[1]) end
-- Sentinel distinguishes an intact empty qualification set from a lost key.
redis.call('SADD', KEYS[2], '0')
for i = 5, #ARGV do redis.call('SADD', KEYS[2], ARGV[i]) end
redis.call('HSET', KEYS[3], 'beginTime', ARGV[2], 'endTime', ARGV[3])
return 1
