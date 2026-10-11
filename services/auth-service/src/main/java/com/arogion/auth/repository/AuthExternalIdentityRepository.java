package com.arogion.auth.repository;

import com.arogion.auth.entity.AuthExternalIdentity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface AuthExternalIdentityRepository extends JpaRepository<AuthExternalIdentity, UUID> {

    Optional<AuthExternalIdentity> findByProviderAndProviderSubject(String provider, String providerSubject);

    java.util.List<AuthExternalIdentity> findByUserId(UUID userId);
}
