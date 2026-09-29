package com.meonggo.backend.auth.repository;

import com.meonggo.backend.auth.exception.AuthErrorCode;
import com.meonggo.backend.auth.exception.RetryableAuthException;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

@Repository
public class RedisLoginAttemptStore implements LoginAttemptStore {
    private static final String PREFIX = "mgbj:auth:login:";
    private static final DefaultRedisScript<Long> CHECK = script("check-login-attempt.lua");
    private static final DefaultRedisScript<Long> FAILURE = script("record-login-failure.lua");
    private static final DefaultRedisScript<Long> SUCCESS = script("reset-login-account.lua");

    private final StringRedisTemplate redis;

    public RedisLoginAttemptStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void check(String accountHash, String ipHash) {
        limit(
                execute(
                        () ->
                                redis.execute(
                                        CHECK,
                                        List.of(
                                                accountBlockKey(accountHash),
                                                ipBlockKey(ipHash)))));
    }

    @Override
    public void failure(String accountHash, String ipHash) {
        limit(
                execute(
                        () ->
                                redis.execute(
                                        FAILURE,
                                        List.of(
                                                accountKey(accountHash),
                                                accountBlockKey(accountHash),
                                                ipKey(ipHash),
                                                ipBlockKey(ipHash)))));
    }

    @Override
    public void success(String accountHash) {
        execute(
                () ->
                        redis.execute(
                                SUCCESS,
                                List.of(accountKey(accountHash), accountBlockKey(accountHash))));
    }

    private static String accountKey(String hash) {
        return PREFIX + "account:" + hash;
    }

    private static String ipKey(String hash) {
        return PREFIX + "ip:" + hash;
    }

    private static String accountBlockKey(String hash) {
        return PREFIX + "account-block:" + hash;
    }

    private static String ipBlockKey(String hash) {
        return PREFIX + "ip-block:" + hash;
    }

    private static void limit(long retryAfter) {
        if (retryAfter > 0) {
            throw new RetryableAuthException(AuthErrorCode.LOGIN_LIMITED, retryAfter);
        }
    }

    private long execute(Supplier<Long> operation) {
        try {
            Long result = operation.get();
            if (result == null) {
                throw unavailable();
            }
            return result;
        } catch (DataAccessException exception) {
            throw unavailable();
        }
    }

    private static RetryableAuthException unavailable() {
        return new RetryableAuthException(AuthErrorCode.AUTH_UNAVAILABLE, 1);
    }

    private static DefaultRedisScript<Long> script(String name) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/" + name));
        script.setResultType(Long.class);
        return script;
    }
}
