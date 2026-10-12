package com.arogion.auth.repository;

import com.arogion.auth.entity.AuthSignupTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface AuthSignupTransactionRepository extends JpaRepository<AuthSignupTransaction, UUID> {
}
