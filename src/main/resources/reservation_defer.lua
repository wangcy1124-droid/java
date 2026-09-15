local clock = redis.call('TIME')
local now = tonumber(clock[1]) * 1000 + math.floor(tonumber(clock[2]) / 1000)
-- XX avoids resurrecting a record that another consumer has just completed.
return redis.call('ZADD', KEYS[1], 'XX', now, ARGV[1])
