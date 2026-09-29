local retry = 0
if redis.call('EXISTS', KEYS[1]) == 1 then retry = math.max(retry, math.ceil(redis.call('PTTL', KEYS[1]) / 1000)) end
if redis.call('EXISTS', KEYS[2]) == 1 then retry = math.max(retry, math.ceil(redis.call('PTTL', KEYS[2]) / 1000)) end
return retry
