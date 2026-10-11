package com.arogion.auth;

import com.arogion.auth.entity.AuthUser;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class JwtService {
	private final SecretKey signingKey;
	private final long expirationSeconds;

	public JwtService(
			@Value("${security.jwt.secret}") String secret,
			@Value("${security.jwt.expiration-seconds:3600}") long expirationSeconds) {
		if (secret.length() < 32) {
			throw new IllegalArgumentException("security.jwt.secret must contain at least 32 characters");
		}
		this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
		this.expirationSeconds = expirationSeconds;
	}

	public String issue(AuthUser user) {
		Instant now = Instant.now();
		return Jwts.builder()
				.subject(user.getId().toString())
				.claim("email", user.getNormalizedEmail())
				.issuedAt(Date.from(now))
				.expiration(Date.from(now.plusSeconds(expirationSeconds)))
				.signWith(signingKey)
				.compact();
	}

	public Claims parse(String token) {
		return Jwts.parser().verifyWith(signingKey).build().parseSignedClaims(token).getPayload();
	}

	public long getExpirationSeconds() {
		return expirationSeconds;
	}

	public UUID getUserId(Claims claims) {
		return UUID.fromString(claims.getSubject());
	}
}
