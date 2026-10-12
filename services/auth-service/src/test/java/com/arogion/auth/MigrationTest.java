package com.arogion.auth;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
public class MigrationTest {

    @Test
    void contextLoadsAndFlywayMigrates() {
        // If the application context loads without exception, 
        // it means Flyway successfully applied the migrations.
    }
}
