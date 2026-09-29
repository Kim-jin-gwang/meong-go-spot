if redis.call('HGET', KEYS[1], 'verificationId') ~= ARGV[1] then return 0 end
if redis.call('HGET', KEYS[1], 'hash') ~= ARGV[2] then return 0 end
if ARGV[3] ~= '1' then
    local attempts = redis.call('HINCRBY', KEYS[1], 'attempts', 1)
    if attempts >= 5 then redis.call('DEL', KEYS[1]) end
    return 0
end
if redis.call('EXISTS', KEYS[2]) == 1 then return 0 end
local time = redis.call('TIME')
local issued = tonumber(time[1])
local expires = issued + 600
redis.call('HSET', KEYS[2], 'secretHash', ARGV[4], 'phoneLookupHash', ARGV[5],
    'status', 'ISSUED', 'issuedAt', issued, 'expiresAt', expires)
redis.call('EXPIREAT', KEYS[2], expires)
redis.call('DEL', KEYS[1])
return 1
