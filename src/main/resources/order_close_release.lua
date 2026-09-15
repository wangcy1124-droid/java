-- KEYS: stock, users, owners, closedMarker. ARGV: userId, reservationId (历史订单可为空)
-- 标记不自动过期，覆盖 Redis 已执行但调用方超时/数据库完成标记未提交的窗口。
local markerType = redis.call('TYPE', KEYS[4]).ok
if markerType == 'string' then return 0 end
if markerType ~= 'none' then return -1 end
if redis.call('TYPE', KEYS[1]).ok ~= 'string' or redis.call('TYPE', KEYS[2]).ok ~= 'set' then return -1 end
local ownersType = redis.call('TYPE', KEYS[3]).ok
if ownersType ~= 'hash' and ownersType ~= 'none' then return -1 end
local stock = redis.call('GET', KEYS[1])
if not string.match(stock, '^%d+$') or tonumber(stock) >= 2147483647 then return -1 end
local owner = redis.call('HGET', KEYS[3], ARGV[1]) or ''
-- 只释放本订单的占用，不能删除同一用户后来获得的新资格。
if owner ~= ARGV[2] or redis.call('SISMEMBER', KEYS[2], ARGV[1]) ~= 1 then return -1 end
redis.call('INCR', KEYS[1])
redis.call('SREM', KEYS[2], ARGV[1])
redis.call('HDEL', KEYS[3], ARGV[1])
redis.call('SET', KEYS[4], '1')
return 1
