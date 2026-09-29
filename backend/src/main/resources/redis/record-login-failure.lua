local existing_retry = 0
if redis.call('EXISTS', KEYS[2]) == 1 then existing_retry = math.max(existing_retry, math.ceil(redis.call('PTTL', KEYS[2]) / 1000)) end
if redis.call('EXISTS', KEYS[4]) == 1 then existing_retry = math.max(existing_retry, math.ceil(redis.call('PTTL', KEYS[4]) / 1000)) end
if existing_retry > 0 then return existing_retry end

local account = redis.call('INCR', KEYS[1])
if account == 1 then redis.call('EXPIRE', KEYS[1], 900) end
local ip = redis.call('INCR', KEYS[3])
if ip == 1 then redis.call('EXPIRE', KEYS[3], 600) end
if account >= 5 then redis.call('SET', KEYS[2], '1', 'NX', 'EX', 900); redis.call('DEL', KEYS[1]) end
if ip >= 20 then redis.call('SET', KEYS[4], '1', 'NX', 'EX', 900); redis.call('DEL', KEYS[3]) end
local retry = 0
if account >= 5 then retry = math.max(retry, math.ceil(redis.call('PTTL', KEYS[2]) / 1000)) end
if ip >= 20 then retry = math.max(retry, math.ceil(redis.call('PTTL', KEYS[4]) / 1000)) end
return retry
