if redis.call('EXISTS', KEYS[2]) == 1 then return 0 end
return redis.call('DEL', KEYS[1])
