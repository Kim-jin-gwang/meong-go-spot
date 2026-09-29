package com.meonggo.backend.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.meonggo.backend.auth.RedisTestServer;
import com.meonggo.backend.auth.exception.AuthErrorCode;
import com.meonggo.backend.auth.exception.RetryableAuthException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.data.redis.core.script.RedisScript;

class RedisPhoneVerificationStoreTest {
    private static final LettuceConnectionFactory CONNECTION_FACTORY = connectionFactory();
    private static final StringRedisTemplate REDIS = new StringRedisTemplate(CONNECTION_FACTORY);

    private RedisPhoneVerificationStore store;

    @BeforeEach
    void setUp() {
        REDIS.getConnectionFactory().getConnection().serverCommands().flushDb();
        store = new RedisPhoneVerificationStore(REDIS);
    }

    @AfterAll
    static void closeRedis() {
        CONNECTION_FACTORY.destroy();
    }

    @Test
    void fifthWrongAttemptConsumesOnlyTheMatchingOtp() {
        assertThat(store.reserveSend("phone-a", "ip-a", "send-a")).isZero();
        assertThat(store.activateOtp("phone-a", "send-a", "verification-a", "otp-hash-a")).isTrue();

        for (int attempt = 0; attempt < 4; attempt++) {
            assertThat(
                            store.confirmOtp(
                                    "phone-a",
                                    "verification-a",
                                    "otp-hash-a",
                                    false,
                                    proof("unused")))
                    .isFalse();
            assertThat(store.findOtp("phone-a")).isPresent();
        }
        assertThat(
                        store.confirmOtp(
                                "phone-a", "verification-a", "otp-hash-a", false, proof("unused")))
                .isFalse();
        assertThat(store.findOtp("phone-a")).isEmpty();
    }

    @Test
    void activatingAcceptedSendReplacesPreviousOtpButRejectedActivationDoesNot() {
        assertThat(store.reserveSend("phone-a", "ip-a", "send-a")).isZero();
        assertThat(store.activateOtp("phone-a", "send-a", "verification-a", "otp-a")).isTrue();

        assertThat(store.activateOtp("phone-a", "wrong-send", "verification-b", "otp-b")).isFalse();
        assertThat(store.findOtp("phone-a"))
                .contains(new PhoneVerificationStore.OtpSnapshot("verification-a", "otp-a"));
    }

    @Test
    void reservationEnforcesCooldownAndSlidingLimits() {
        assertThat(store.reserveSend("phone-a", "ip-a", "send-1")).isZero();
        assertThat(store.reserveSend("phone-a", "ip-a", "send-2")).isBetween(1L, 60L);

        for (int index = 2; index <= 5; index++) {
            REDIS.delete("mgbj:auth:cooldown:phone-a");
            REDIS.delete("mgbj:auth:reservation:phone-a");
            assertThat(store.reserveSend("phone-a", "ip-" + index, "send-" + index)).isZero();
        }
        REDIS.delete("mgbj:auth:cooldown:phone-a");
        REDIS.delete("mgbj:auth:reservation:phone-a");
        assertThat(store.reserveSend("phone-a", "new-ip", "send-6")).isBetween(1L, 3600L);

        for (int index = 0; index < 20; index++) {
            assertThat(store.reserveSend("ip-phone-" + index, "shared-ip", "ip-send-" + index))
                    .isZero();
        }
        assertThat(store.reserveSend("ip-phone-limit", "shared-ip", "ip-last"))
                .isBetween(1L, 3600L);
    }

    @Test
    void onlyOneConcurrentReservationForPhoneIsAccepted() throws Exception {
        try (var executor = Executors.newFixedThreadPool(8)) {
            List<Callable<Long>> tasks = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                String requestId = "send-" + index;
                tasks.add(() -> store.reserveSend("phone-race", "ip-race", requestId));
            }
            long accepted =
                    executor.invokeAll(tasks).stream()
                            .filter(
                                    future -> {
                                        try {
                                            return future.get() == 0;
                                        } catch (Exception exception) {
                                            throw new AssertionError(exception);
                                        }
                                    })
                            .count();
            assertThat(accepted).isOne();
        }
    }

    @Test
    void confirmationUsesRedisTimeAndCreatesTenMinuteProof() {
        assertThat(store.reserveSend("phone-a", "ip-a", "send-a")).isZero();
        assertThat(store.activateOtp("phone-a", "send-a", "verification-a", "otp-a")).isTrue();
        var callerTimes =
                new PhoneVerificationStore.ProofRecord(
                        "selector-a",
                        "secret-a",
                        "phone-a",
                        Instant.EPOCH,
                        Instant.EPOCH.plusSeconds(1));

        Instant before = Instant.now();
        assertThat(store.confirmOtp("phone-a", "verification-a", "otp-a", true, callerTimes))
                .isTrue();
        var stored = store.findProof("selector-a").orElseThrow();

        assertThat(stored.issuedAt()).isAfterOrEqualTo(before.minusSeconds(1));
        assertThat(stored.expiresAt()).isEqualTo(stored.issuedAt().plusSeconds(600));
        assertThat(stored.status()).isEqualTo("ISSUED");
        assertThat(store.findOtp("phone-a")).isEmpty();
    }

    @Test
    void existingProofSelectorDoesNotConsumeOtpOrOverwriteProof() {
        createProof("occupied");
        assertThat(store.reserveSend("phone-b", "ip-b", "send-b")).isZero();
        assertThat(store.activateOtp("phone-b", "send-b", "verification-b", "otp-b")).isTrue();

        assertThat(
                        store.confirmOtp(
                                "phone-b",
                                "verification-b",
                                "otp-b",
                                true,
                                new PhoneVerificationStore.ProofRecord(
                                        "occupied",
                                        "different-secret",
                                        "phone-b",
                                        Instant.EPOCH,
                                        Instant.EPOCH)))
                .isFalse();
        assertThat(store.findOtp("phone-b")).isPresent();
        assertThat(store.findProof("occupied").orElseThrow().secretHash())
                .isEqualTo("secret-occupied");
    }

    @Test
    void staleConfirmationDoesNotConsumeCurrentOtpAndExpiredOtpIsAbsent() {
        assertThat(store.reserveSend("phone-a", "ip-a", "send-a")).isZero();
        assertThat(store.activateOtp("phone-a", "send-a", "verification-a", "otp-a")).isTrue();

        for (int attempt = 0; attempt < 5; attempt++) {
            assertThat(
                            store.confirmOtp(
                                    "phone-a", "old-verification", "otp-a", false, proof("unused")))
                    .isFalse();
        }
        assertThat(store.findOtp("phone-a")).isPresent();
        REDIS.expire("mgbj:auth:otp:phone-a", Duration.ZERO);
        assertThat(store.findOtp("phone-a")).isEmpty();
    }

    @Test
    void redisFailureIsTranslatedWithoutOriginalCause() {
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
            RedisPhoneVerificationStore unavailableStore =
                    new RedisPhoneVerificationStore(new StringRedisTemplate(unavailable));
            assertThatThrownBy(() -> unavailableStore.findOtp("phone-a"))
                    .isInstanceOf(RetryableAuthException.class)
                    .hasFieldOrPropertyWithValue("errorCode", AuthErrorCode.AUTH_UNAVAILABLE)
                    .hasNoCause();
        } finally {
            unavailable.destroy();
        }
    }

    @Test
    void outOfRangeProofTimestampIsTreatedAsUnavailable() {
        String key = "mgbj:auth:proof:corrupt";
        REDIS.opsForHash()
                .putAll(
                        key,
                        java.util.Map.of(
                                "secretHash", "secret",
                                "phoneLookupHash", "phone",
                                "status", "ISSUED",
                                "issuedAt", Long.toString(Long.MAX_VALUE),
                                "expiresAt", Long.toString(Long.MAX_VALUE)));

        assertThatThrownBy(() -> store.findProof("corrupt"))
                .isInstanceOf(RetryableAuthException.class)
                .hasNoCause();
    }

    @Test
    @SuppressWarnings("unchecked")
    void nullTransitionResultIsTreatedAsUnavailable() {
        StringRedisTemplate nullReturningRedis = mock(StringRedisTemplate.class);
        when(nullReturningRedis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(null);
        RedisPhoneVerificationStore nullReturningStore =
                new RedisPhoneVerificationStore(nullReturningRedis);

        assertThatThrownBy(() -> nullReturningStore.restoreProof("selector", "request"))
                .isInstanceOf(RetryableAuthException.class)
                .hasNoCause();
    }

    @Test
    void proofClaimHasSingleWinnerAndOnlyOwnerCanRestoreOrConsume() throws Exception {
        createProof("selector-a");
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Callable<Boolean>> claims =
                    List.of(
                            () ->
                                    store.claimProof(
                                            "selector-a",
                                            "secret-selector-a",
                                            "phone-a",
                                            "claim-a"),
                            () ->
                                    store.claimProof(
                                            "selector-a",
                                            "secret-selector-a",
                                            "phone-a",
                                            "claim-b"));
            var results = executor.invokeAll(claims);
            assertThat(
                            results.stream()
                                    .filter(
                                            result -> {
                                                try {
                                                    return result.get();
                                                } catch (Exception exception) {
                                                    throw new AssertionError(exception);
                                                }
                                            }))
                    .hasSize(1);
        }

        String owner =
                REDIS.<String, String>opsForHash()
                        .get("mgbj:auth:proof:selector-a", "claimRequestId");
        String nonOwner = owner.equals("claim-a") ? "claim-b" : "claim-a";
        store.restoreProof("selector-a", nonOwner);
        assertThat(store.findProof("selector-a").orElseThrow().status()).isEqualTo("CLAIMED");
        store.restoreProof("selector-a", owner);
        assertThat(store.findProof("selector-a").orElseThrow().status()).isEqualTo("ISSUED");

        assertThat(store.claimProof("selector-a", "secret-selector-a", "phone-a", owner)).isTrue();
        store.consumeProof("selector-a", nonOwner);
        assertThat(store.findProof("selector-a").orElseThrow().status()).isEqualTo("CLAIMED");
        store.consumeProof("selector-a", owner);
        assertThat(store.findProof("selector-a").orElseThrow().status()).isEqualTo("CONSUMED");
    }

    private void createProof(String selector) {
        assertThat(store.reserveSend("phone-a", "ip-a", "send-a")).isZero();
        assertThat(store.activateOtp("phone-a", "send-a", "verification-a", "otp-a")).isTrue();
        assertThat(store.confirmOtp("phone-a", "verification-a", "otp-a", true, proof(selector)))
                .isTrue();
    }

    private PhoneVerificationStore.ProofRecord proof(String selector) {
        return new PhoneVerificationStore.ProofRecord(
                selector,
                "secret-" + selector,
                "phone-a",
                Instant.EPOCH,
                Instant.EPOCH.plusSeconds(1));
    }

    private static LettuceConnectionFactory connectionFactory() {
        URI uri = URI.create(RedisTestServer.url());
        RedisStandaloneConfiguration configuration =
                new RedisStandaloneConfiguration(uri.getHost(), uri.getPort());
        if (uri.getUserInfo() != null) {
            String[] credentials = uri.getUserInfo().split(":", 2);
            if (credentials.length == 2) {
                configuration.setUsername(credentials[0]);
                configuration.setPassword(credentials[1]);
            } else {
                configuration.setPassword(credentials[0]);
            }
        }
        String path = uri.getPath();
        if (path != null && path.length() > 1) {
            configuration.setDatabase(Integer.parseInt(path.substring(1)));
        }
        LettuceConnectionFactory factory = new LettuceConnectionFactory(configuration);
        factory.afterPropertiesSet();
        return factory;
    }
}
