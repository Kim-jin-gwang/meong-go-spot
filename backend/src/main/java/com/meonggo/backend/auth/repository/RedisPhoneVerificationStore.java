package com.meonggo.backend.auth.repository;

import com.meonggo.backend.auth.exception.AuthErrorCode;
import com.meonggo.backend.auth.exception.RetryableAuthException;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

@Repository
public class RedisPhoneVerificationStore implements PhoneVerificationStore {
    private static final String PREFIX = "mgbj:auth:";
    private static final DefaultRedisScript<Long> RESERVE =
            script("reserve-phone-verification.lua");
    private static final DefaultRedisScript<Long> ACTIVATE = script("activate-phone-otp.lua");
    private static final DefaultRedisScript<Long> CONFIRM = script("confirm-phone-otp.lua");
    private static final DefaultRedisScript<Long> CLAIM = script("claim-phone-proof.lua");
    private static final DefaultRedisScript<Long> TRANSITION = script("transition-phone-proof.lua");

    private final StringRedisTemplate redis;

    public RedisPhoneVerificationStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public long reserveSend(String phoneHash, String ipHash, String requestId) {
        return redis(
                () ->
                        value(
                                redis.execute(
                                        RESERVE,
                                        List.of(
                                                key("rate:phone:", phoneHash),
                                                key("rate:ip:", ipHash),
                                                key("cooldown:", phoneHash),
                                                key("reservation:", phoneHash)),
                                        requestId)));
    }

    @Override
    public boolean activateOtp(
            String phoneHash, String requestId, String verificationId, String otpHash) {
        return redis(
                () ->
                        value(
                                        redis.execute(
                                                ACTIVATE,
                                                List.of(
                                                        key("reservation:", phoneHash),
                                                        key("cooldown:", phoneHash),
                                                        key("otp:", phoneHash)),
                                                requestId,
                                                verificationId,
                                                otpHash))
                                == 1);
    }

    @Override
    public Optional<OtpSnapshot> findOtp(String phoneHash) {
        return redis(
                () -> {
                    List<Object> values =
                            redis.opsForHash()
                                    .multiGet(
                                            key("otp:", phoneHash),
                                            List.of("verificationId", "hash"));
                    if (values.size() != 2 || values.get(0) == null || values.get(1) == null) {
                        return Optional.empty();
                    }
                    return Optional.of(
                            new OtpSnapshot(values.get(0).toString(), values.get(1).toString()));
                });
    }

    @Override
    public boolean confirmOtp(
            String phoneHash,
            String verificationId,
            String expectedHash,
            boolean matched,
            ProofRecord proof) {
        return redis(
                () ->
                        value(
                                        redis.execute(
                                                CONFIRM,
                                                List.of(
                                                        key("otp:", phoneHash),
                                                        key("proof:", proof.selector())),
                                                verificationId,
                                                expectedHash,
                                                matched ? "1" : "0",
                                                proof.secretHash(),
                                                proof.phoneLookupHash()))
                                == 1);
    }

    @Override
    public Optional<ProofSnapshot> findProof(String selector) {
        return redis(
                () -> {
                    Map<Object, Object> values =
                            redis.opsForHash().entries(key("proof:", selector));
                    if (values.isEmpty()) {
                        return Optional.empty();
                    }
                    Object secretHash = values.get("secretHash");
                    Object phoneHash = values.get("phoneLookupHash");
                    Object status = values.get("status");
                    Object issuedAt = values.get("issuedAt");
                    Object expiresAt = values.get("expiresAt");
                    if (secretHash == null
                            || phoneHash == null
                            || status == null
                            || issuedAt == null
                            || expiresAt == null) {
                        return Optional.empty();
                    }
                    return Optional.of(
                            new ProofSnapshot(
                                    secretHash.toString(),
                                    phoneHash.toString(),
                                    status.toString(),
                                    Instant.ofEpochSecond(Long.parseLong(issuedAt.toString())),
                                    Instant.ofEpochSecond(Long.parseLong(expiresAt.toString()))));
                });
    }

    @Override
    public boolean claimProof(
            String selector, String secretHash, String phoneHash, String requestId) {
        return redis(
                () ->
                        value(
                                        redis.execute(
                                                CLAIM,
                                                List.of(key("proof:", selector)),
                                                secretHash,
                                                phoneHash,
                                                requestId))
                                == 1);
    }

    @Override
    public void restoreProof(String selector, String requestId) {
        transitionProof(selector, requestId, "ISSUED");
    }

    @Override
    public void consumeProof(String selector, String requestId) {
        transitionProof(selector, requestId, "CONSUMED");
    }

    private void transitionProof(String selector, String requestId, String status) {
        redis(
                () ->
                        value(
                                redis.execute(
                                        TRANSITION,
                                        List.of(key("proof:", selector)),
                                        requestId,
                                        status)));
    }

    private static String key(String namespace, String hash) {
        return PREFIX + namespace + hash;
    }

    private static long value(Long result) {
        if (result == null) {
            throw unavailable();
        }
        return result;
    }

    private <T> T redis(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (DataAccessException | DateTimeException | NumberFormatException exception) {
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
