package com.meonggo.backend.push.service;

import com.meonggo.backend.auth.exception.AuthErrorCode;
import com.meonggo.backend.global.error.BusinessException;
import com.meonggo.backend.global.error.CommonErrorCode;
import com.meonggo.backend.push.repository.PushDeviceRepository;
import com.meonggo.backend.push.security.PushTokenProtection;
import com.meonggo.backend.push.web.PushDeviceInput;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PushDeviceService {
    private final PushDeviceRepository devices;
    private final PushTokenProtection protection;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public PushDeviceService(
            PushDeviceRepository devices,
            PushTokenProtection protection,
            @Qualifier("authClock") Clock clock,
            PlatformTransactionManager manager) {
        this.devices = devices;
        this.protection = protection;
        this.clock = clock;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setTimeout(15);
    }

    public void register(
            long sessionId, long memberId, UUID installationId, PushDeviceInput input) {
        PushTokenProtection.ProtectedToken token;
        try {
            token = protection.protect(input.token());
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
        execute(
                () -> {
                    Instant now = now();
                    devices.lockRegistrationKeys(installationId, token.lookupHash());
                    requireSession(sessionId, memberId, now);
                    devices.reassign(
                            sessionId,
                            installationId,
                            input.platform(),
                            token.ciphertext(),
                            token.lookupHash(),
                            now);
                });
    }

    public void unregister(long sessionId, long memberId, UUID installationId) {
        execute(() -> devices.clear(sessionId, memberId, installationId));
    }

    private void requireSession(long sessionId, long memberId, Instant now) {
        if (!devices.ownsActiveSession(sessionId, memberId, now))
            throw new BusinessException(AuthErrorCode.AUTHENTICATION_REQUIRED);
    }

    private void execute(Runnable action) {
        try {
            transaction.executeWithoutResult(status -> action.run());
        } catch (BusinessException exception) {
            throw exception;
        } catch (DataAccessException | IllegalStateException exception) {
            throw new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
