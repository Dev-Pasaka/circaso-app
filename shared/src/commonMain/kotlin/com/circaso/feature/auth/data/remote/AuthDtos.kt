package com.circaso.feature.auth.data.remote

import com.circaso.core.session.SessionDto
import kotlinx.serialization.Serializable

// Named exactly like the schemas in the integration guide (section 11).

@Serializable data class NonceResponse(val nonce: String, val expiresInSeconds: Int)

@Serializable data class AppleNameDto(val given: String? = null, val family: String? = null)

@Serializable
data class DeviceDto(
    val deviceId: String,
    val platform: String,
    val model: String,
    val osVersion: String? = null,
    val appVersion: String,
)

@Serializable data class DeviceProofDto(val timestamp: String, val signature: String)

@Serializable
data class SocialSignInRequest(
    val provider: String,
    val idToken: String,
    val nonce: String,
    val appleName: AppleNameDto? = null,
    val device: DeviceDto,
    val deviceProof: DeviceProofDto? = null,
)

@Serializable
data class SignInResponse(
    val next: String,
    val maskedPhone: String? = null,
    val session: SessionDto,
    val agentSession: SessionDto? = null,
)

@Serializable data class StartPhoneRequest(val phone: String)

@Serializable
data class StartPhoneResponse(
    val verificationId: String,
    val codeLength: Int,
    val expiresInSeconds: Int,
    val resendAfterSeconds: Int,
)

@Serializable data class VerifyCodeRequest(val verificationId: String, val code: String)

@Serializable data class CarrierRequest(val carrierToken: String)

@Serializable data class PhoneVerifiedResponse(val phoneVerified: Boolean, val maskedPhone: String, val next: String)

@Serializable
data class RegisterDeviceRequest(
    val deviceId: String,
    val publicKey: String,
    val keyAlgorithm: String = "ES256",
    val pushToken: String? = null,
    val attestation: String? = null,
    val model: String? = null,
)

@Serializable data class UserStateDto(val userId: String, val status: String)

@Serializable data class ReplacedDeviceDto(val model: String, val lastSeenAt: String)

@Serializable
data class RegisterDeviceResponse(
    val user: UserStateDto,
    val session: SessionDto,
    val agentSession: SessionDto,
    val replacedDevice: ReplacedDeviceDto? = null,
)
