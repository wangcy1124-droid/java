-- Bind only while this reservation remains active. Reaper may have won already.
if redis.call('HGET', KEYS[1], ARGV[1]) ~= ARGV[2] then return 0 end
if redis.call('EXISTS', KEYS[2]) == 0 then return 0 end
redis.call('HSET', KEYS[2], 'orderId', ARGV[3])
return 1
