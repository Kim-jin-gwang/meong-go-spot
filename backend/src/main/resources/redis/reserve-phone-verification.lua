local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
local hour = 3600000
local day = 86400000

redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now - day)
redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', now - hour)

local retry = 0
local cooldownTtl = redis.call('PTTL', KEYS[3])
if cooldownTtl > 0 then retry = math.max(retry, math.ceil(cooldownTtl / 1000)) end

local phoneHourStart = '(' .. (now - hour)
local phoneDayStart = '(' .. (now - day)
local phoneHour = redis.call('ZCOUNT', KEYS[1], phoneHourStart, '+inf')
if phoneHour >= 5 then
    local oldest = redis.call('ZRANGEBYSCORE', KEYS[1], phoneHourStart, '+inf', 'WITHSCORES', 'LIMIT', 0, 1)
    retry = math.max(retry, math.ceil((tonumber(oldest[2]) + hour - now) / 1000))
end
local phoneDay = redis.call('ZCOUNT', KEYS[1], phoneDayStart, '+inf')
if phoneDay >= 10 then
    local oldest = redis.call('ZRANGEBYSCORE', KEYS[1], phoneDayStart, '+inf', 'WITHSCORES', 'LIMIT', 0, 1)
    retry = math.max(retry, math.ceil((tonumber(oldest[2]) + day - now) / 1000))
end
local ipHour = redis.call('ZCOUNT', KEYS[2], phoneHourStart, '+inf')
if ipHour >= 20 then
    local oldest = redis.call('ZRANGEBYSCORE', KEYS[2], phoneHourStart, '+inf', 'WITHSCORES', 'LIMIT', 0, 1)
    retry = math.max(retry, math.ceil((tonumber(oldest[2]) + hour - now) / 1000))
end
if retry > 0 then return retry end

redis.call('ZADD', KEYS[1], now, ARGV[1])
redis.call('PEXPIRE', KEYS[1], day)
redis.call('ZADD', KEYS[2], now, ARGV[1])
redis.call('PEXPIRE', KEYS[2], hour)
redis.call('SET', KEYS[3], ARGV[1], 'PX', 60000)
redis.call('SET', KEYS[4], ARGV[1], 'PX', 60000)
return 0
