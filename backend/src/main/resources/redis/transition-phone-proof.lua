if redis.call('HGET', KEYS[1], 'status') ~= 'CLAIMED' then return 0 end
if redis.call('HGET', KEYS[1], 'claimRequestId') ~= ARGV[1] then return 0 end
redis.call('HSET', KEYS[1], 'status', ARGV[2])
redis.call('HDEL', KEYS[1], 'claimRequestId')
return 1
