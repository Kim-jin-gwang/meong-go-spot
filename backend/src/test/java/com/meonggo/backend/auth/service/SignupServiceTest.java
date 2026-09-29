package com.meonggo.backend.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.meonggo.backend.auth.dto.SignupRequest;
import com.meonggo.backend.auth.dto.SignupResponse;
import com.meonggo.backend.auth.exception.AuthErrorCode;
import com.meonggo.backend.auth.exception.RetryableAuthException;
import com.meonggo.backend.auth.repository.PhoneVerificationStore;
import com.meonggo.backend.auth.repository.PhoneVerificationStore.ProofSnapshot;
import com.meonggo.backend.auth.security.PasswordWork;
import com.meonggo.backend.auth.security.PhoneProtection;
import com.meonggo.backend.auth.security.SignupInputPolicy;
import com.meonggo.backend.auth.security.TestPhoneSignupBypass;
import com.meonggo.backend.global.error.BusinessException;
import com.meonggo.backend.global.error.CommonErrorCode;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SignupServiceTest {
    @Mock private SignupInputPolicy inputs;
    @Mock private PhoneProtection protection;
    @Mock private PasswordWork passwords;
    @Mock private PasswordWork.Permit permit;
    @Mock private PhoneVerificationStore store;
    @Mock private MemberCreationTransaction creation;
    private SignupService service;
    private PhoneProof proof;
    private SignupRequest request;

    @BeforeEach
    void setup() {
        service =
                new SignupService(
                        inputs,
                        protection,
                        passwords,
                        store,
                        creation,
                        new TestPhoneSignupBypass(false));
        proof = PhoneProof.generate();
        request =
                new SignupRequest(
                        "Login",
                        "valid-password-value",
                        "닉네임",
                        "01012345678",
                        proof.token(),
                        true,
                        "privacy-collection-v1");
        when(inputs.canonicalLoginId("Login")).thenReturn("login");
        when(inputs.normalizePassword("valid-password-value", "login"))
                .thenReturn("valid-password-value");
        when(inputs.normalizeNickname("닉네임")).thenReturn("닉네임");
        when(protection.normalize("01012345678")).thenReturn("+821012345678");
        when(protection.lookupHash("+821012345678")).thenReturn("phone-hash");
        when(store.findProof(proof.selector()))
                .thenReturn(
                        Optional.of(
                                new ProofSnapshot(
                                        proof.secretHash(),
                                        "phone-hash",
                                        "ISSUED",
                                        Instant.now(),
                                        Instant.now().plusSeconds(600))));
    }

    @Test
    void saturationLeavesProofIssuedAndDoesNoHashWork() {
        when(passwords.acquire()).thenThrow(unavailable());
        assertThatThrownBy(() -> service.signup(request))
                .isInstanceOf(RetryableAuthException.class);
        verify(store, never()).claimProof(anyString(), anyString(), anyString(), anyString());
        verify(creation, never()).create(any(), anyString());
    }

    @Test
    void lostClaimReleasesPermitWithoutEncoding() {
        when(passwords.acquire()).thenReturn(permit);
        when(store.claimProof(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(false);
        assertThatThrownBy(() -> service.signup(request)).isInstanceOf(BusinessException.class);
        verify(permit, never()).encode(anyString());
        verify(permit).close();
    }

    @Test
    void encodeFailureRestoresOnlyOwnClaimAndReleasesPermit() {
        acquired();
        when(permit.encode(anyString()))
                .thenThrow(new IllegalStateException("test-raw-password-must-not-leak"));
        assertThatThrownBy(() -> service.signup(request))
                .isInstanceOf(RetryableAuthException.class)
                .hasNoCause()
                .hasMessageNotContaining("test-raw-password");
        verify(store).restoreProof(eq(proof.selector()), anyString());
        verify(permit).close();
        verify(creation, never()).create(any(), anyString());
    }

    @Test
    void committedMemberReturnsSuccessEvenWhenConsumptionFails() {
        preparedMember();
        SignupResponse response = new SignupResponse(1L, "login", "닉네임", Instant.now());
        when(creation.create(any(), eq("phone-hash"))).thenReturn(response);
        doThrow(unavailable()).when(store).consumeProof(eq(proof.selector()), anyString());
        assertThat(service.signup(request)).isSameAs(response);
        var order = inOrder(passwords, store, permit, creation);
        order.verify(store).findProof(proof.selector());
        order.verify(passwords).acquire();
        order.verify(store)
                .claimProof(
                        eq(proof.selector()),
                        eq(proof.secretHash()),
                        eq("phone-hash"),
                        anyString());
        order.verify(permit).encode("valid-password-value");
        order.verify(creation).create(any(), eq("phone-hash"));
        order.verify(store).consumeProof(eq(proof.selector()), anyString());
        order.verify(permit).close();
        verify(store, never()).restoreProof(anyString(), anyString());
    }

    @Test
    void uncertainDatabaseCommitRetainsClaim() {
        preparedMember();
        when(creation.create(any(), anyString()))
                .thenThrow(
                        new MemberCreationException(CommonErrorCode.INTERNAL_SERVER_ERROR, false));
        assertThatThrownBy(() -> service.signup(request))
                .isInstanceOf(MemberCreationException.class);
        verify(store, never()).restoreProof(anyString(), anyString());
        verify(store, never()).consumeProof(anyString(), anyString());
        verify(permit).close();
    }

    @Test
    void confirmedRollbackRestoresClaimEvenIfRecoveryRedisFails() {
        preparedMember();
        when(creation.create(any(), anyString()))
                .thenThrow(
                        new MemberCreationException(CommonErrorCode.INTERNAL_SERVER_ERROR, true));
        doThrow(unavailable()).when(store).restoreProof(anyString(), anyString());
        assertThatThrownBy(() -> service.signup(request))
                .isInstanceOf(MemberCreationException.class);
        verify(store).restoreProof(eq(proof.selector()), anyString());
        verify(permit).close();
    }

    private void acquired() {
        when(passwords.acquire()).thenReturn(permit);
        when(store.claimProof(anyString(), anyString(), anyString(), anyString())).thenReturn(true);
    }

    private void preparedMember() {
        acquired();
        when(permit.encode(anyString())).thenReturn("test-hash");
        when(protection.encrypt(anyString())).thenReturn("test-envelope");
    }

    private RetryableAuthException unavailable() {
        return new RetryableAuthException(AuthErrorCode.AUTH_UNAVAILABLE, 1);
    }
}
