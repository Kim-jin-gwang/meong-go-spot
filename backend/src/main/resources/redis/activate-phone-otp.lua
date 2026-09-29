if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
redis.call('HSET', KEYS[3], 'verificationId', ARGV[2], 'hash', ARGV[3], 'attempts', 0)
redis.call('PEXPIREAT', KEYS[3], now + 180000)
redis.call('SET', KEYS[2], ARGV[1], 'PX', 60000)
redis.call('DEL', KEYS[1])
return 1
