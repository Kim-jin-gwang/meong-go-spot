local time = redis.call('TIME')
local now = tonumber(time[1])
if redis.call('HGET', KEYS[1], 'secretHash') ~= ARGV[1] then return 0 end
if redis.call('HGET', KEYS[1], 'phoneLookupHash') ~= ARGV[2] then return 0 end
if redis.call('HGET', KEYS[1], 'status') ~= 'ISSUED' then return 0 end
if tonumber(redis.call('HGET', KEYS[1], 'expiresAt') or '0') <= now then return 0 end
redis.call('HSET', KEYS[1], 'status', 'CLAIMED', 'claimRequestId', ARGV[3])
return 1
