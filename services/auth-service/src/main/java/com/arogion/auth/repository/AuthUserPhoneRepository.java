package com.arogion.auth.repository;

import com.arogion.auth.entity.AuthUserPhone;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface AuthUserPhoneRepository extends JpaRepository<AuthUserPhone, UUID> {

    Optional<AuthUserPhone> findByNormalizedPhoneAndVerifiedAtIsNotNull(String normalizedPhone);

    Optional<AuthUserPhone> findByUserIdAndPrimaryTrue(UUID userId);
}
