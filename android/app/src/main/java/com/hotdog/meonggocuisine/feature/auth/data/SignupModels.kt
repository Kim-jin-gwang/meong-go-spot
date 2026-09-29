package com.hotdog.meonggocuisine.feature.auth.data

import kotlinx.serialization.Serializable

@Serializable
data class PhoneVerificationRequest(
    val phoneNumber: String,
    val privacyCollectionAgreed: Boolean,
    val privacyCollectionPolicyVersion: String,
)

@Serializable
data class PhoneConfirmationRequest(
    val phoneNumber: String,
    val verificationCode: String,
    val privacyCollectionAgreed: Boolean,
    val privacyCollectionPolicyVersion: String,
)

@Serializable
data class PhoneVerificationResponse(
    val phoneVerificationToken: String,
    val expiresAt: String,
)

@Serializable
data class SignupRequest(
    val loginId: String,
    val password: String,
    val nickname: String,
    val phoneNumber: String,
    val phoneVerificationToken: String,
    val privacyCollectionAgreed: Boolean,
    val privacyCollectionPolicyVersion: String,
)

@Serializable
data class SignupResponse(
    val memberId: Long,
    val loginId: String,
    val nickname: String,
    val createdAt: String,
)

const val PRIVACY_COLLECTION_POLICY_VERSION = "privacy-collection-v1"

@Serializable
data class LoginIdAvailabilityResponse(
    val available: Boolean,
)
