package com.arogion.auth.repository;

import com.arogion.auth.entity.AuthVerificationChallenge;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface AuthVerificationChallengeRepository extends JpaRepository<AuthVerificationChallenge, UUID> {

    Optional<AuthVerificationChallenge> findTopByTransactionIdAndChannelAndConsumedAtIsNullOrderByCreatedAtDesc(
            UUID transactionId, String channel);

    Optional<AuthVerificationChallenge> findTopByUserIdAndChannelAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(
            UUID userId, String channel, String purpose);

    Optional<AuthVerificationChallenge> findTopByUserIdAndChannelAndPurposeAndDestinationAndConsumedAtIsNullOrderByCreatedAtDesc(
            UUID userId, String channel, String purpose, String destination);
}
