package com.arogion.auth;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AuthDtos {
    private AuthDtos() {}

    // ── Email Signup ─────────────────────────────────────────

    public record EmailSignupRequest(@NotBlank @Email String email) {}

    public record RequestOtpResponse(String transactionId) {}

    public record VerifyOtpRequest(
            @NotBlank String transactionId,
            @NotBlank String otp) {}

    public record CompleteEmailSignupRequest(
            @NotBlank String transactionId,
            @NotBlank @Size(min = 8, max = 128) String password) {}

    // ── Email Login ──────────────────────────────────────────

    public record LoginRequest(
            @NotBlank @Email String email,
            @NotBlank String password) {}

    // ── Auth Response (issued tokens) ────────────────────────

    public record AuthResponse(
            String accessToken,
            String tokenType,
            long expiresInSeconds,
            String userId,
            String email) {}

    // ── Google OAuth ─────────────────────────────────────────

    public record GoogleAuthRequest(@NotBlank String idToken) {}

    // ── Phone OTP for Signup ─────────────────────────────────

    public record PhoneOtpRequest(
            String transactionId,
            @NotBlank String phoneNumber) {}

    // ── Phone Login ──────────────────────────────────────────

    public record PhoneLoginRequest(@NotBlank String phoneNumber) {}

    public record PhoneLoginVerifyRequest(
            @NotBlank String transactionId,
            @NotBlank String otp) {}

    // ── Profile Management ───────────────────────────────────

    public record ProfileEmailRequest(@NotBlank @Email String email) {}

    public record ProfileEmailVerifyRequest(
            @NotBlank @Email String email,
            @NotBlank String otp) {}

    public record ProfilePasswordRequest(
            @NotBlank @Size(min = 8, max = 128) String password) {}

    public record ProfileEmailResponse(
            String status,
            String message,
            String email) {}

    public record UserProfileResponse(
            String userId,
            String email,
            boolean emailVerified,
            String phoneNumber,
            boolean phoneVerified,
            java.util.List<String> linkedProviders,
            String status) {}

    // ── Signup Status Response ───────────────────────────────
    // Used for intermediate signup steps. Null fields are omitted from JSON.

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SignupStatusResponse(
            String status,
            String transactionId,
            String message,
            String accessToken,
            String tokenType,
            Long expiresInSeconds,
            String userId,
            String email) {

        /** Factory for intermediate pending steps (no auth fields). */
        public static SignupStatusResponse pending(String status, String transactionId, String message) {
            return new SignupStatusResponse(status, transactionId, message,
                    null, null, null, null, null);
        }

        /** Factory for completed signup / existing-user login (includes auth fields). */
        public static SignupStatusResponse completed(AuthResponse auth, String transactionId) {
            return new SignupStatusResponse("COMPLETED", transactionId,
                    "Account activated successfully.",
                    auth.accessToken(), auth.tokenType(), auth.expiresInSeconds(),
                    auth.userId(), auth.email());
        }
    }
}
