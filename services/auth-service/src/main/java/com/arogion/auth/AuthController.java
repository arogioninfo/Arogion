package com.arogion.auth;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    // ── Email Signup Flow ────────────────────────────────────

    /** Step 1: Request email OTP — sends a real OTP via SMTP */
    @PostMapping("/signup/email/request-otp")
    public AuthDtos.RequestOtpResponse requestEmailOtp(
            @Validated @RequestBody AuthDtos.EmailSignupRequest request) {
        return authService.requestEmailSignupOtp(request);
    }

    /** Step 2: Verify email OTP — returns PHONE_VERIFICATION_REQUIRED */
    @PostMapping("/signup/email/verify-otp")
    public AuthDtos.SignupStatusResponse verifyEmailOtp(
            @Validated @RequestBody AuthDtos.VerifyOtpRequest request) {
        return authService.verifyEmailOtp(request);
    }

    /**
     * Step 3: Request phone OTP for signup (hardcoded 123456).
     * - If transactionId is omitted: starts a standalone mobile-only signup.
     * - If transactionId is provided: continues an email/Google signup.
     */
    @PostMapping("/signup/phone/request-otp")
    public AuthDtos.RequestOtpResponse requestPhoneOtp(
            @Validated @RequestBody AuthDtos.PhoneOtpRequest request) {
        return authService.requestPhoneOtp(request);
    }

    /** Step 4: Verify phone OTP — for Mobile/Google: returns tokens; for email: returns SET_PASSWORD */
    @PostMapping("/signup/phone/verify-otp")
    public AuthDtos.SignupStatusResponse verifyPhoneOtp(
            @Validated @RequestBody AuthDtos.VerifyOtpRequest request) {
        return authService.verifyPhoneOtp(request);
    }

    /** Step 5: Complete email signup by setting a password */
    @PostMapping("/signup/email/complete")
    public AuthDtos.AuthResponse completeEmailSignup(
            @Validated @RequestBody AuthDtos.CompleteEmailSignupRequest request) {
        return authService.completeEmailSignup(request);
    }

    // ── Email Login ──────────────────────────────────────────

    @PostMapping("/signin/email")
    public AuthDtos.AuthResponse emailLogin(
            @Validated @RequestBody AuthDtos.LoginRequest request) {
        return authService.login(request);
    }

    // ── Google OAuth ─────────────────────────────────────────

    /** Accepts a Google ID token. Returns tokens for existing users or pending signup for new. */
    @PostMapping("/google")
    public AuthDtos.SignupStatusResponse googleAuth(
            @Validated @RequestBody AuthDtos.GoogleAuthRequest request) {
        return authService.googleAuth(request);
    }

    // ── Phone Login ──────────────────────────────────────────

    /** Request OTP for phone-based login (hardcoded 123456) */
    @PostMapping("/signin/phone/request-otp")
    public AuthDtos.RequestOtpResponse requestPhoneLoginOtp(
            @Validated @RequestBody AuthDtos.PhoneLoginRequest request) {
        return authService.requestPhoneLoginOtp(request);
    }

    /** Verify phone login OTP — returns Arogion tokens */
    @PostMapping("/signin/phone/verify-otp")
    public AuthDtos.AuthResponse verifyPhoneLoginOtp(
            @Validated @RequestBody AuthDtos.PhoneLoginVerifyRequest request) {
        return authService.verifyPhoneLoginOtp(request);
    }

    // ── Profile Management (Authenticated) ───────────────────

    /** Retrieves the profile of the current authenticated user */
    @GetMapping("/profile")
    public AuthDtos.UserProfileResponse getProfile(Principal principal) {
        UUID userId = UUID.fromString(principal.getName());
        return authService.getUserProfile(userId);
    }

    /** Request OTP to add or verify email in profile (checks if email is taken) */
    @PostMapping("/profile/email/request-otp")
    public AuthDtos.ProfileEmailResponse requestProfileEmailOtp(
            Principal principal,
            @Validated @RequestBody AuthDtos.ProfileEmailRequest request) {
        UUID userId = UUID.fromString(principal.getName());
        return authService.requestProfileEmailOtp(userId, request);
    }

    /** Verify OTP and link email to the current account */
    @PostMapping("/profile/email/verify-otp")
    public AuthDtos.UserProfileResponse verifyProfileEmailOtp(
            Principal principal,
            @Validated @RequestBody AuthDtos.ProfileEmailVerifyRequest request) {
        UUID userId = UUID.fromString(principal.getName());
        return authService.verifyProfileEmailOtp(userId, request);
    }

    /** Set or change password in profile */
    @PostMapping("/profile/password")
    public ResponseEntity<Void> setProfilePassword(
            Principal principal,
            @Validated @RequestBody AuthDtos.ProfilePasswordRequest request) {
        UUID userId = UUID.fromString(principal.getName());
        authService.setProfilePassword(userId, request);
        return ResponseEntity.ok().build();
    }
}
