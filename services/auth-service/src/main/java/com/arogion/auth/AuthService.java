package com.arogion.auth;

import com.arogion.auth.entity.*;
import com.arogion.auth.repository.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class AuthService {

    private final AuthUserRepository userRepository;
    private final AuthCredentialRepository credentialRepository;
    private final AuthSignupTransactionRepository transactionRepository;
    private final AuthUserPhoneRepository phoneRepository;
    private final AuthExternalIdentityRepository externalIdentityRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final OtpService otpService;
    private final EmailService emailService;
    private final GoogleTokenVerifier googleTokenVerifier;

    public AuthService(AuthUserRepository userRepository,
                       AuthCredentialRepository credentialRepository,
                       AuthSignupTransactionRepository transactionRepository,
                       AuthUserPhoneRepository phoneRepository,
                       AuthExternalIdentityRepository externalIdentityRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       OtpService otpService,
                       EmailService emailService,
                       GoogleTokenVerifier googleTokenVerifier) {
        this.userRepository = userRepository;
        this.credentialRepository = credentialRepository;
        this.transactionRepository = transactionRepository;
        this.phoneRepository = phoneRepository;
        this.externalIdentityRepository = externalIdentityRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.otpService = otpService;
        this.emailService = emailService;
        this.googleTokenVerifier = googleTokenVerifier;
    }

    // ═══════════════════════════════════════════════════════════
    //  PHONE NORMALIZATION & LOOKUP HELPERS
    // ═══════════════════════════════════════════════════════════

    public static String normalizePhone(String rawPhone) {
        if (rawPhone == null) return null;
        return rawPhone.replaceAll("[\\s\\-\\(\\)]", "").trim();
    }

    private Optional<AuthUserPhone> findUserPhone(String phone) {
        if (phone == null) return Optional.empty();
        String normalized = normalizePhone(phone);
        Optional<AuthUserPhone> result = phoneRepository
                .findByNormalizedPhoneAndVerifiedAtIsNotNull(normalized);
        if (result.isPresent()) {
            return result;
        }
        if (!normalized.startsWith("+") && normalized.length() == 10) {
            result = phoneRepository.findByNormalizedPhoneAndVerifiedAtIsNotNull("+91" + normalized);
            if (result.isPresent()) return result;
        } else if (normalized.startsWith("+91") && normalized.length() == 13) {
            result = phoneRepository.findByNormalizedPhoneAndVerifiedAtIsNotNull(normalized.substring(3));
            if (result.isPresent()) return result;
        }
        return Optional.empty();
    }

    // ═══════════════════════════════════════════════════════════
    //  EMAIL SIGNUP FLOW
    // ═══════════════════════════════════════════════════════════

    /**
     * Step 1: Request email OTP.
     * Creates a signup transaction, generates OTP, sends via SMTP.
     */
    @Transactional
    public AuthDtos.RequestOtpResponse requestEmailSignupOtp(AuthDtos.EmailSignupRequest request) {
        String email = request.email().trim().toLowerCase();

        userRepository.findByNormalizedEmail(email).ifPresent(user -> {
            if ("ACTIVE".equals(user.getStatus())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "An account already exists for this email");
            }
        });

        AuthSignupTransaction txn = new AuthSignupTransaction();
        txn.setSignupMethod("EMAIL");
        txn.setStatus("EMAIL_OTP_PENDING");
        txn.setEmail(email);
        txn.setExpiresAt(OffsetDateTime.now().plusMinutes(15));
        txn = transactionRepository.save(txn);

        String otp = otpService.generateEmailOtp();
        otpService.createChallenge(txn.getId(), null, "EMAIL", "SIGNUP", email, otp);
        emailService.sendOtp(email, otp);

        return new AuthDtos.RequestOtpResponse(txn.getId().toString());
    }

    /**
     * Step 2: Verify email OTP.
     * Marks email as verified, transitions to phone verification step.
     */
    @Transactional
    public AuthDtos.SignupStatusResponse verifyEmailOtp(AuthDtos.VerifyOtpRequest request) {
        UUID txnId = UUID.fromString(request.transactionId());
        AuthSignupTransaction txn = findActiveTransaction(txnId);

        if (!"EMAIL_OTP_PENDING".equals(txn.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Email OTP already verified or transaction in unexpected state");
        }

        otpService.verifyChallengeForTransaction(txnId, "EMAIL", request.otp());

        txn.setEmailVerifiedAt(OffsetDateTime.now());
        txn.setStatus("PHONE_OTP_PENDING");
        transactionRepository.save(txn);

        return AuthDtos.SignupStatusResponse.pending("PHONE_VERIFICATION_REQUIRED",
                txn.getId().toString(),
                "Email verified. Verify your phone number to continue signup.");
    }

    // ═══════════════════════════════════════════════════════════
    //  PHONE SIGNUP (Both Direct Mobile-Only and Step-3 in Email/Google)
    // ═══════════════════════════════════════════════════════════

    /**
     * Step 3: Request phone OTP for signup.
     * - If transactionId is omitted/blank: starts standalone mobile-only signup.
     * - If transactionId is present: continues the Email or Google signup flow.
     */
    @Transactional
    public AuthDtos.RequestOtpResponse requestPhoneOtp(AuthDtos.PhoneOtpRequest request) {
        String phone = normalizePhone(request.phoneNumber());

        // Case A: Standalone mobile-only signup (no prior transaction)
        if (request.transactionId() == null || request.transactionId().isBlank()) {
            if (findUserPhone(phone).isPresent()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "An account with this phone number already exists. Please sign in instead.");
            }

            AuthSignupTransaction txn = new AuthSignupTransaction();
            txn.setSignupMethod("PHONE");
            txn.setStatus("PHONE_OTP_PENDING");
            txn.setPhone(phone);
            txn.setExpiresAt(OffsetDateTime.now().plusMinutes(15));
            txn = transactionRepository.save(txn);

            String otp = otpService.generatePhoneOtp();
            otpService.createChallenge(txn.getId(), null, "SMS", "SIGNUP", phone, otp);

            return new AuthDtos.RequestOtpResponse(txn.getId().toString());
        }

        // Case B: Continuation of multi-step signup (Email or Google)
        UUID txnId = UUID.fromString(request.transactionId());
        AuthSignupTransaction txn = findActiveTransaction(txnId);

        if (!"PHONE_OTP_PENDING".equals(txn.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Phone OTP not expected at this step");
        }

        txn.setPhone(phone);
        transactionRepository.save(txn);

        String otp = otpService.generatePhoneOtp();
        otpService.createChallenge(txn.getId(), null, "SMS", "SIGNUP", phone, otp);

        return new AuthDtos.RequestOtpResponse(txn.getId().toString());
    }

    /**
     * Step 4: Verify phone OTP for signup.
     * - For Mobile-only signups: creates account with phone immediately and returns tokens.
     * - For Google signups: interlinks with existing account or creates new account, returns tokens.
     * - For Email signups: transitions to password-setting step (SET_PASSWORD).
     */
    @Transactional
    public AuthDtos.SignupStatusResponse verifyPhoneOtp(AuthDtos.VerifyOtpRequest request) {
        UUID txnId = UUID.fromString(request.transactionId());
        AuthSignupTransaction txn = findActiveTransaction(txnId);

        if (!"PHONE_OTP_PENDING".equals(txn.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Phone OTP not expected at this step");
        }

        otpService.verifyChallengeForTransaction(txnId, "SMS", request.otp());
        txn.setPhoneVerifiedAt(OffsetDateTime.now());

        // 1. Mobile-only signup: activate immediately (no email or password needed)
        if ("PHONE".equals(txn.getSignupMethod())) {
            txn.setStatus("COMPLETED");
            transactionRepository.save(txn);
            AuthUser user = createMobileOnlyUser(txn);
            return AuthDtos.SignupStatusResponse.completed(buildResponse(user), txn.getId().toString());
        }

        // 2. Google signup: complete immediately and interlink
        if ("GOOGLE".equals(txn.getSignupMethod())) {
            txn.setStatus("COMPLETED");
            transactionRepository.save(txn);
            AuthUser user = createOrLinkGoogleUser(txn);
            return AuthDtos.SignupStatusResponse.completed(buildResponse(user), txn.getId().toString());
        }

        // 3. Email signup: require password next
        txn.setStatus("PHONE_VERIFIED");
        transactionRepository.save(txn);

        return AuthDtos.SignupStatusResponse.pending("SET_PASSWORD",
                txn.getId().toString(),
                "Phone verified. Set your password to complete signup.");
    }

    /**
     * Step 5: Complete email signup by setting a password.
     * Creates or interlinks the user, credential, and phone records.
     */
    @Transactional
    public AuthDtos.AuthResponse completeEmailSignup(AuthDtos.CompleteEmailSignupRequest request) {
        UUID txnId = UUID.fromString(request.transactionId());
        AuthSignupTransaction txn = findActiveTransaction(txnId);

        if (!"PHONE_VERIFIED".equals(txn.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Email and phone verification required before completing signup");
        }

        String phone = normalizePhone(txn.getPhone());
        String email = txn.getEmail() != null ? txn.getEmail().toLowerCase().trim() : null;

        // Check if an account already exists with this verified phone number
        Optional<AuthUserPhone> existingPhone = findUserPhone(phone);
        AuthUser user = null;

        if (existingPhone.isPresent()) {
            AuthUser userByPhone = userRepository.findById(existingPhone.get().getUserId()).orElse(null);
            if (userByPhone != null) {
                // If existing phone account has no email, or matches this email -> link them!
                if (userByPhone.getNormalizedEmail() == null) {
                    userByPhone.setNormalizedEmail(email);
                    userByPhone.setEmailVerifiedAt(txn.getEmailVerifiedAt());
                    user = userRepository.save(userByPhone);
                } else if (email != null && userByPhone.getNormalizedEmail().equalsIgnoreCase(email)) {
                    user = userByPhone;
                } else {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Phone number already registered to another account");
                }
            }
        }

        if (user == null && email != null) {
            user = userRepository.findByNormalizedEmail(email).orElse(null);
        }

        if (user == null) {
            user = new AuthUser();
            user.setNormalizedEmail(email);
            user.setEmailVerifiedAt(txn.getEmailVerifiedAt());
            user.setStatus("ACTIVE");
            user = userRepository.save(user);
        } else {
            user.setStatus("ACTIVE");
            if (txn.getEmailVerifiedAt() != null) {
                user.setEmailVerifiedAt(txn.getEmailVerifiedAt());
            }
            user = userRepository.save(user);
        }

        // Create or update password credential
        AuthCredential credential = credentialRepository.findByUserId(user.getId())
                .orElse(new AuthCredential());
        credential.setUser(user);
        credential.setPasswordHash(passwordEncoder.encode(request.password()));
        credentialRepository.save(credential);

        // Ensure verified phone record is linked to this user
        createPhoneRecord(user.getId(), phone, txn.getPhoneVerifiedAt());

        txn.setStatus("COMPLETED");
        txn.setUserId(user.getId());
        transactionRepository.save(txn);

        return buildResponse(user);
    }

    // ═══════════════════════════════════════════════════════════
    //  EMAIL LOGIN
    // ═══════════════════════════════════════════════════════════

    public AuthDtos.AuthResponse login(AuthDtos.LoginRequest request) {
        AuthUser user = userRepository
                .findByNormalizedEmail(request.email().trim().toLowerCase())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "Invalid email or password"));

        AuthCredential credential = credentialRepository.findByUserId(user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "Invalid email or password"));

        if (!passwordEncoder.matches(request.password(), credential.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password");
        }

        if (!"ACTIVE".equals(user.getStatus())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Account is not active");
        }

        return buildResponse(user);
    }

    // ═══════════════════════════════════════════════════════════
    //  GOOGLE OAUTH
    // ═══════════════════════════════════════════════════════════

    /**
     * Handles Google sign-in / sign-up.
     * - Existing linked active user → returns Arogion tokens immediately.
     * - Email matches an existing active user → links Google identity, returns tokens.
     * - New identity → creates pending signup requiring phone verification.
     */
    @Transactional
    public AuthDtos.SignupStatusResponse googleAuth(AuthDtos.GoogleAuthRequest request) {
        GoogleTokenVerifier.GoogleUserInfo googleUser = googleTokenVerifier.verify(request.idToken());

        // 1. Check if Google identity is already linked to a user
        Optional<AuthExternalIdentity> existingIdentity = externalIdentityRepository
                .findByProviderAndProviderSubject("GOOGLE", googleUser.sub());

        if (existingIdentity.isPresent()) {
            AuthUser user = userRepository.findById(existingIdentity.get().getUserId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                            "Linked user not found"));

            if ("ACTIVE".equals(user.getStatus())) {
                return AuthDtos.SignupStatusResponse.completed(buildResponse(user), null);
            }
        }

        // 2. Check if email matches an existing active user (shared UUID rule)
        if (googleUser.emailVerified() && googleUser.email() != null) {
            String normalizedEmail = googleUser.email().trim().toLowerCase();
            Optional<AuthUser> existingUser = userRepository.findByNormalizedEmail(normalizedEmail);

            if (existingUser.isPresent() && "ACTIVE".equals(existingUser.get().getStatus())) {
                // Link Google identity to the existing user
                if (existingIdentity.isEmpty()) {
                    AuthExternalIdentity identity = new AuthExternalIdentity();
                    identity.setUserId(existingUser.get().getId());
                    identity.setProvider("GOOGLE");
                    identity.setProviderSubject(googleUser.sub());
                    identity.setProviderEmail(googleUser.email());
                    externalIdentityRepository.save(identity);
                }
                return AuthDtos.SignupStatusResponse.completed(
                        buildResponse(existingUser.get()), null);
            }
        }

        // 3. New identity — create pending signup requiring phone verification
        AuthSignupTransaction txn = new AuthSignupTransaction();
        txn.setSignupMethod("GOOGLE");
        txn.setStatus("PHONE_OTP_PENDING");
        txn.setEmail(googleUser.emailVerified()
                ? googleUser.email().trim().toLowerCase() : null);
        txn.setEmailVerifiedAt(googleUser.emailVerified()
                ? OffsetDateTime.now() : null);
        txn.setProviderSubject(googleUser.sub());
        txn.setExpiresAt(OffsetDateTime.now().plusMinutes(15));
        txn = transactionRepository.save(txn);

        return AuthDtos.SignupStatusResponse.pending("PHONE_VERIFICATION_REQUIRED",
                txn.getId().toString(),
                "Verify your phone number to complete signup.");
    }

    // ═══════════════════════════════════════════════════════════
    //  PHONE LOGIN
    // ═══════════════════════════════════════════════════════════

    /**
     * Initiates phone-based login.
     * Finds the user by verified phone number (whether created via Google OAuth,
     * Mobile signup, or Email signup), creates OTP challenge (hardcoded 123456).
     */
    @Transactional
    public AuthDtos.RequestOtpResponse requestPhoneLoginOtp(AuthDtos.PhoneLoginRequest request) {
        String phone = normalizePhone(request.phoneNumber());

        AuthUserPhone userPhone = findUserPhone(phone)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No account found with this phone number"));

        AuthUser user = userRepository.findById(userPhone.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "User not found"));

        if (!"ACTIVE".equals(user.getStatus())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Account is not active");
        }

        String otp = otpService.generatePhoneOtp();
        AuthVerificationChallenge challenge = otpService.createChallenge(
                null, user.getId(), "SMS", "LOGIN", userPhone.getNormalizedPhone(), otp);

        return new AuthDtos.RequestOtpResponse(challenge.getId().toString());
    }

    /**
     * Completes phone-based login by verifying the OTP.
     * Opens the exact same account associated with this verified phone number.
     */
    @Transactional
    public AuthDtos.AuthResponse verifyPhoneLoginOtp(AuthDtos.PhoneLoginVerifyRequest request) {
        UUID challengeId = UUID.fromString(request.transactionId());

        AuthVerificationChallenge challenge = otpService.findChallengeById(challengeId);

        if (!"LOGIN".equals(challenge.getPurpose()) || !"SMS".equals(challenge.getChannel())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid verification type");
        }

        otpService.verifyChallengeById(challengeId, request.otp());

        AuthUser user = userRepository.findById(challenge.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "User not found"));

        if (!"ACTIVE".equals(user.getStatus())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Account is not active");
        }

        return buildResponse(user);
    }

    // ═══════════════════════════════════════════════════════════
    //  PROFILE MANAGEMENT (Add/Verify Email, Get Profile, Set Password)
    // ═══════════════════════════════════════════════════════════

    /**
     * Request OTP to add or verify email in profile section.
     * Rejects if the email already belongs to another account.
     */
    @Transactional
    public AuthDtos.ProfileEmailResponse requestProfileEmailOtp(UUID userId, AuthDtos.ProfileEmailRequest request) {
        AuthUser currentUser = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        String email = request.email().trim().toLowerCase();

        // Check if this email is already registered to ANOTHER account
        Optional<AuthUser> existingUser = userRepository.findByNormalizedEmail(email);
        if (existingUser.isPresent() && !existingUser.get().getId().equals(currentUser.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This email is already associated with another account. Please enter another email to verify.");
        }

        String otp = otpService.generateEmailOtp();
        otpService.createChallenge(null, currentUser.getId(), "EMAIL", "EMAIL_LINK", email, otp);
        emailService.sendOtp(email, otp);

        return new AuthDtos.ProfileEmailResponse("OTP_SENT",
                "Verification code sent to " + email, email);
    }

    /**
     * Verifies the email OTP and links the email to the user's account.
     */
    @Transactional
    public AuthDtos.UserProfileResponse verifyProfileEmailOtp(UUID userId, AuthDtos.ProfileEmailVerifyRequest request) {
        AuthUser currentUser = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        String email = request.email().trim().toLowerCase();

        // Re-check conflict
        Optional<AuthUser> existingUser = userRepository.findByNormalizedEmail(email);
        if (existingUser.isPresent() && !existingUser.get().getId().equals(currentUser.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This email is already associated with another account. Please enter another email to verify.");
        }

        // Verify OTP challenge
        otpService.verifyChallengeForUser(currentUser.getId(), "EMAIL", "EMAIL_LINK", email, request.otp());

        currentUser.setNormalizedEmail(email);
        currentUser.setEmailVerifiedAt(OffsetDateTime.now());
        currentUser = userRepository.save(currentUser);

        return getUserProfile(userId);
    }

    /**
     * Retrieves the profile of the authenticated user.
     */
    @Transactional(readOnly = true)
    public AuthDtos.UserProfileResponse getUserProfile(UUID userId) {
        AuthUser user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        Optional<AuthUserPhone> primaryPhone = phoneRepository.findByUserIdAndPrimaryTrue(userId);

        List<String> providers = externalIdentityRepository.findByUserId(userId).stream()
                .filter(java.util.Objects::nonNull)
                .map(identity -> identity.getProvider())
                .filter(java.util.Objects::nonNull)
                .toList();

        String primaryPhoneNumber = primaryPhone.map(p -> p.getNormalizedPhone()).orElse(null);
        boolean isPhoneVerified = primaryPhone.map(p -> p.getVerifiedAt() != null).orElse(false);

        return new AuthDtos.UserProfileResponse(
                user.getId().toString(),
                user.getNormalizedEmail(),
                user.getEmailVerifiedAt() != null,
                primaryPhoneNumber,
                isPhoneVerified,
                providers,
                user.getStatus());
    }

    /**
     * Set password in profile (for mobile-only or Google OAuth users who want email/password sign-in).
     */
    @Transactional
    public void setProfilePassword(UUID userId, AuthDtos.ProfilePasswordRequest request) {
        AuthUser user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        AuthCredential credential = credentialRepository.findByUserId(user.getId())
                .orElse(new AuthCredential());
        credential.setUser(user);
        credential.setPasswordHash(passwordEncoder.encode(request.password()));
        credentialRepository.save(credential);
    }

    // ═══════════════════════════════════════════════════════════
    //  PRIVATE HELPERS
    // ═══════════════════════════════════════════════════════════

    private AuthSignupTransaction findActiveTransaction(UUID txnId) {
        AuthSignupTransaction txn = transactionRepository.findById(txnId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Transaction not found"));

        if (txn.getExpiresAt().isBefore(OffsetDateTime.now())) {
            throw new ResponseStatusException(HttpStatus.GONE, "Transaction expired");
        }
        if ("COMPLETED".equals(txn.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Transaction already completed");
        }
        return txn;
    }

    /**
     * Creates account for mobile-only signup.
     */
    private AuthUser createMobileOnlyUser(AuthSignupTransaction txn) {
        String phone = normalizePhone(txn.getPhone());
        Optional<AuthUserPhone> existingPhone = findUserPhone(phone);
        if (existingPhone.isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Phone number already registered to another account");
        }

        AuthUser user = new AuthUser();
        user.setNormalizedEmail(null);
        user.setEmailVerifiedAt(null);
        user.setStatus("ACTIVE");
        user = userRepository.save(user);

        createPhoneRecord(user.getId(), phone, txn.getPhoneVerifiedAt());

        txn.setUserId(user.getId());
        transactionRepository.save(txn);

        return user;
    }

    /**
     * Creates or links the Arogion user for a completed Google OAuth signup.
     * Ensures phone number and email are interlinked to the same account.
     */
    private AuthUser createOrLinkGoogleUser(AuthSignupTransaction txn) {
        String phone = normalizePhone(txn.getPhone());
        String email = txn.getEmail() != null ? txn.getEmail().toLowerCase().trim() : null;

        // Check if an account already exists with this phone number
        Optional<AuthUserPhone> existingPhone = findUserPhone(phone);
        AuthUser user = null;

        if (existingPhone.isPresent()) {
            AuthUser userByPhone = userRepository.findById(existingPhone.get().getUserId()).orElse(null);
            if (userByPhone != null) {
                // If phone user has no email yet, link this Google email to that user!
                if (userByPhone.getNormalizedEmail() == null) {
                    userByPhone.setNormalizedEmail(email);
                    userByPhone.setEmailVerifiedAt(txn.getEmailVerifiedAt());
                    user = userRepository.save(userByPhone);
                } else if (email != null && userByPhone.getNormalizedEmail().equalsIgnoreCase(email)) {
                    user = userByPhone;
                } else {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Phone number is already associated with another account with a different email");
                }
            }
        }

        // If no user found by phone, check by email
        if (user == null && email != null) {
            user = userRepository.findByNormalizedEmail(email).orElse(null);
        }

        // If still no user, create a new one
        if (user == null) {
            user = new AuthUser();
            user.setNormalizedEmail(email);
            user.setEmailVerifiedAt(txn.getEmailVerifiedAt());
            user.setStatus("ACTIVE");
            user = userRepository.save(user);
        } else {
            user.setStatus("ACTIVE");
            if (user.getEmailVerifiedAt() == null && txn.getEmailVerifiedAt() != null) {
                user.setEmailVerifiedAt(txn.getEmailVerifiedAt());
            }
            user = userRepository.save(user);
        }

        // Link Google external identity
        if (txn.getProviderSubject() != null) {
            Optional<AuthExternalIdentity> existingIdentity = externalIdentityRepository
                    .findByProviderAndProviderSubject("GOOGLE", txn.getProviderSubject());
            if (existingIdentity.isEmpty()) {
                AuthExternalIdentity identity = new AuthExternalIdentity();
                identity.setUserId(user.getId());
                identity.setProvider("GOOGLE");
                identity.setProviderSubject(txn.getProviderSubject());
                identity.setProviderEmail(email);
                externalIdentityRepository.save(identity);
            }
        }

        // Ensure verified phone record is linked to this user
        createPhoneRecord(user.getId(), phone, txn.getPhoneVerifiedAt());

        txn.setUserId(user.getId());
        transactionRepository.save(txn);

        return user;
    }

    private void createPhoneRecord(UUID userId, String phone, OffsetDateTime verifiedAt) {
        if (phone == null) return;
        String normalized = normalizePhone(phone);

        Optional<AuthUserPhone> existing = findUserPhone(normalized);

        if (existing.isPresent()) {
            if (!existing.get().getUserId().equals(userId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Phone number already registered to another account");
            }
            return;
        }

        AuthUserPhone phoneRecord = new AuthUserPhone();
        phoneRecord.setUserId(userId);
        phoneRecord.setNormalizedPhone(normalized);
        phoneRecord.setPrimary(true);
        phoneRecord.setVerifiedAt(verifiedAt != null ? verifiedAt : OffsetDateTime.now());
        phoneRepository.save(phoneRecord);
    }

    private AuthDtos.AuthResponse buildResponse(AuthUser user) {
        return new AuthDtos.AuthResponse(
                jwtService.issue(user), "Bearer",
                jwtService.getExpirationSeconds(),
                user.getId().toString(),
                user.getNormalizedEmail());
    }
}
