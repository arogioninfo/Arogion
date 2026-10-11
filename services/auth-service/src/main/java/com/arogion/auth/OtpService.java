package com.arogion.auth;

import com.arogion.auth.entity.AuthVerificationChallenge;
import com.arogion.auth.repository.AuthVerificationChallengeRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Handles OTP generation, hashing, storage, and verification for both
 * email and phone channels. Phone OTP is hardcoded to 123456 for development.
 */
@Service
public class OtpService {

    private final AuthVerificationChallengeRepository challengeRepository;

    private static final int MAX_ATTEMPTS = 5;
    private static final int OTP_EXPIRY_MINUTES = 5;
    private static final String PHONE_HARDCODE_OTP = "123456";

    public OtpService(AuthVerificationChallengeRepository challengeRepository) {
        this.challengeRepository = challengeRepository;
    }

    /**
     * Generates a cryptographically random 6-digit OTP for email verification.
     */
    public String generateEmailOtp() {
        SecureRandom random = new SecureRandom();
        int otp = 100000 + random.nextInt(900000);
        return String.valueOf(otp);
    }

    /**
     * Returns the hardcoded phone OTP (123456) for development.
     */
    public String generatePhoneOtp() {
        return PHONE_HARDCODE_OTP;
    }

    /**
     * Creates and persists an OTP challenge record. The OTP is stored as a SHA-256 hash.
     *
     * @param transactionId signup transaction ID (for signup flows, mutually exclusive with userId)
     * @param userId        user ID (for login flows, mutually exclusive with transactionId)
     * @param channel       EMAIL or SMS
     * @param purpose       SIGNUP or LOGIN
     * @param destination   email address or phone number
     * @param otp           the plaintext OTP (will be hashed before storage)
     * @return the persisted challenge entity
     */
    public AuthVerificationChallenge createChallenge(UUID transactionId, UUID userId,
                                                      String channel, String purpose,
                                                      String destination, String otp) {
        AuthVerificationChallenge challenge = new AuthVerificationChallenge();
        challenge.setTransactionId(transactionId);
        challenge.setUserId(userId);
        challenge.setChannel(channel);
        challenge.setPurpose(purpose);
        challenge.setDestination(destination);
        challenge.setChallengeHash(hashOtp(otp));
        challenge.setExpiresAt(OffsetDateTime.now().plusMinutes(OTP_EXPIRY_MINUTES));
        return challengeRepository.save(challenge);
    }

    /**
     * Verifies an OTP against the latest unconsumed challenge for the given signup transaction and channel.
     */
    public void verifyChallengeForTransaction(UUID transactionId, String channel, String otp) {
        AuthVerificationChallenge challenge = challengeRepository
                .findTopByTransactionIdAndChannelAndConsumedAtIsNullOrderByCreatedAtDesc(transactionId, channel)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No active verification challenge found"));
        verifyChallenge(challenge, otp);
    }

    /**
     * Retrieves a challenge by its ID (used to inspect purpose/channel before verifying).
     */
    public AuthVerificationChallenge findChallengeById(UUID challengeId) {
        return challengeRepository.findById(challengeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Verification challenge not found"));
    }

    /**
     * Verifies an OTP against the latest unconsumed challenge for the given user, channel, purpose, and destination.
     */
    public void verifyChallengeForUser(UUID userId, String channel, String purpose, String destination, String otp) {
        AuthVerificationChallenge challenge = challengeRepository
                .findTopByUserIdAndChannelAndPurposeAndDestinationAndConsumedAtIsNullOrderByCreatedAtDesc(
                        userId, channel, purpose, destination)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No active verification challenge found"));
        verifyChallenge(challenge, otp);
    }

    /**
     * Verifies an OTP against a specific challenge looked up by its ID (used for phone login).
     */
    public void verifyChallengeById(UUID challengeId, String otp) {
        AuthVerificationChallenge challenge = challengeRepository.findById(challengeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Verification challenge not found"));
        verifyChallenge(challenge, otp);
    }

    private void verifyChallenge(AuthVerificationChallenge challenge, String otp) {
        if (challenge.getConsumedAt() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "OTP already used");
        }
        if (challenge.getExpiresAt().isBefore(OffsetDateTime.now())) {
            throw new ResponseStatusException(HttpStatus.GONE, "OTP expired");
        }
        if (challenge.getAttemptCount() >= MAX_ATTEMPTS) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many OTP attempts");
        }

        challenge.setAttemptCount(challenge.getAttemptCount() + 1);

        if (!hashOtp(otp).equals(challenge.getChallengeHash())) {
            challengeRepository.save(challenge);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid OTP");
        }

        challenge.setConsumedAt(OffsetDateTime.now());
        challengeRepository.save(challenge);
    }

    private String hashOtp(String otp) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(otp.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
