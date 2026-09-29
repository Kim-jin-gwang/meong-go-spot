package com.meonggo.backend.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meonggo.backend.auth.RedisTestServer;
import com.meonggo.backend.auth.exception.AuthErrorCode;
import com.meonggo.backend.auth.exception.RetryableAuthException;
import com.meonggo.backend.auth.security.LoginIdentityProtection;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

class RedisLoginAttemptStoreTest {
    private static final LettuceConnectionFactory CONNECTION_FACTORY = connectionFactory();
    private static final StringRedisTemplate REDIS = new StringRedisTemplate(CONNECTION_FACTORY);

    private RedisLoginAttemptStore store;

    @BeforeEach
    void setUp() {
        REDIS.getConnectionFactory().getConnection().serverCommands().flushDb();
        store = new RedisLoginAttemptStore(REDIS);
    }

    @AfterAll
    static void closeRedis() {
        CONNECTION_FACTORY.destroy();
    }

    @Test
    void fifthAccountFailureIsLimitedWithoutExtendingFixedWindow() throws Exception {
        for (int attempt = 0; attempt < 4; attempt++) {
            store.failure("account-a", "ip-" + attempt);
        }
        REDIS.expire("mgbj:auth:login:account:account-a", Duration.ofSeconds(2));

        assertLimited(() -> store.failure("account-a", "ip-last"));
        assertThat(ttl("mgbj:auth:login:account-block:account-a")).isBetween(898L, 900L);
        Thread.sleep(1100);
        assertLimited(() -> store.check("account-a", "another-ip"));

        assertThat(ttl("mgbj:auth:login:account-block:account-a")).isBetween(897L, 899L);
    }

    @Test
    void twentiethIpFailureIsLimitedAtomicallyUnderConcurrency() throws Exception {
        try (var executor = Executors.newFixedThreadPool(20)) {
            List<Callable<Boolean>> failures = new ArrayList<>();
            for (int index = 0; index < 20; index++) {
                String account = "account-" + index;
                failures.add(
                        () -> {
                            try {
                                store.failure(account, "shared-ip");
                                return false;
                            } catch (RetryableAuthException exception) {
                                return exception.errorCode() == AuthErrorCode.LOGIN_LIMITED;
                            }
                        });
            }
            long limited =
                    executor.invokeAll(failures).stream()
                            .filter(
                                    result -> {
                                        try {
                                            return result.get();
                                        } catch (Exception exception) {
                                            throw new AssertionError(exception);
                                        }
                                    })
                            .count();
            assertThat(limited).isOne();
        }
        assertThat(REDIS.hasKey("mgbj:auth:login:ip-block:shared-ip")).isTrue();
    }

    @Test
    void successClearsAccountFailuresButKeepsIpFailures() {
        store.failure("account-a", "ip-a");
        store.success("account-a");

        assertThat(REDIS.hasKey("mgbj:auth:login:account:account-a")).isFalse();
        assertThat(REDIS.opsForValue().get("mgbj:auth:login:ip:ip-a")).isEqualTo("1");
    }

    @Test
    void successArrivingAfterAccountBlockDoesNotClearTheBlock() {
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                store.failure("account-a", "ip-" + attempt);
            } catch (RetryableAuthException ignored) {
                // The threshold failure establishes the block.
            }
        }

        store.success("account-a");

        assertLimited(() -> store.check("account-a", "new-ip"));
    }

    @Test
    void twentiethIpFailureStartsFullFifteenMinuteBlockNearWindowExpiry() {
        for (int attempt = 0; attempt < 19; attempt++) {
            store.failure("account-" + attempt, "shared-ip");
        }
        REDIS.expire("mgbj:auth:login:ip:shared-ip", Duration.ofSeconds(1));

        assertLimited(() -> store.failure("last-account", "shared-ip"));

        assertThat(ttl("mgbj:auth:login:ip-block:shared-ip")).isBetween(898L, 900L);
    }

    @Test
    void failureRejectedByExistingAccountLimitDoesNotCreateAnotherIpWindow() {
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                store.failure("account-a", "original-ip");
            } catch (RetryableAuthException ignored) {
                // The fifth failure establishes the limit.
            }
        }

        assertLimited(() -> store.failure("account-a", "new-ip"));

        assertThat(REDIS.hasKey("mgbj:auth:login:ip:new-ip")).isFalse();
    }

    @Test
    void redisFailureMapsToAuthUnavailableWithoutCause() {
        RedisStandaloneConfiguration configuration =
                new RedisStandaloneConfiguration("127.0.0.1", 1);
        LettuceClientConfiguration client =
                LettuceClientConfiguration.builder()
                        .commandTimeout(Duration.ofMillis(100))
                        .shutdownTimeout(Duration.ZERO)
                        .build();
        LettuceConnectionFactory unavailable = new LettuceConnectionFactory(configuration, client);
        unavailable.afterPropertiesSet();
        try {
            RedisLoginAttemptStore unavailableStore =
                    new RedisLoginAttemptStore(new StringRedisTemplate(unavailable));
            assertThatThrownBy(() -> unavailableStore.check("private-account", "private-ip"))
                    .isInstanceOf(RetryableAuthException.class)
                    .hasFieldOrPropertyWithValue("errorCode", AuthErrorCode.AUTH_UNAVAILABLE)
                    .hasFieldOrPropertyWithValue("retryAfterSeconds", 1L)
                    .hasNoCause();
        } finally {
            unavailable.destroy();
        }
    }

    @Test
    void redisKeysContainOnlyPurposeProtectedIdentifiers() {
        String account = "private-login-id";
        String ip = "203.0.113.10";
        LoginIdentityProtection protection =
                new LoginIdentityProtection(new byte[32], filledKey((byte) 1));

        store.failure(
                protection.accountHash(account),
                protection.ipHash(new byte[] {(byte) 203, 0, 113, 10}));

        assertThat(REDIS.keys("mgbj:auth:login:*"))
                .allMatch(key -> !key.contains(account) && !key.contains(ip));
    }

    private static void assertLimited(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(RetryableAuthException.class)
                .hasFieldOrPropertyWithValue("errorCode", AuthErrorCode.LOGIN_LIMITED)
                .hasNoCause();
    }

    private static long ttl(String key) {
        return REDIS.getExpire(key);
    }

    private static LettuceConnectionFactory connectionFactory() {
        URI uri = URI.create(RedisTestServer.url());
        RedisStandaloneConfiguration configuration =
                new RedisStandaloneConfiguration(uri.getHost(), uri.getPort());
        LettuceConnectionFactory factory = new LettuceConnectionFactory(configuration);
        factory.afterPropertiesSet();
        return factory;
    }

    private static byte[] filledKey(byte value) {
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, value);
        return key;
    }
}
