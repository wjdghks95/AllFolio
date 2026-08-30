package com.allfolio.infra.security;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/ROADMAP.md Task 019 — Refresh Token 발급·해시 검증.
 */
class RefreshTokenIssuerTest {

    private static final JwtProperties PROPERTIES = new JwtProperties(
            "0123456789abcdef0123456789abcdef",
            Duration.ofMinutes(15),
            Duration.ofDays(14)
    );

    private final RefreshTokenIssuer issuer = new RefreshTokenIssuer(PROPERTIES);

    @Test
    void issueProducesDifferentTokenEachTime() {
        Set<String> tokens = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            tokens.add(issuer.issue());
        }
        assertThat(tokens).hasSize(100);
    }

    @Test
    void hashIsDeterministicForSameInput() {
        String token = issuer.issue();

        String first = issuer.hash(token);
        String second = issuer.hash(token);

        assertThat(first).isEqualTo(second);
        assertThat(first).hasSize(64);
    }

    @Test
    void hashDiffersForDifferentInput() {
        String tokenA = issuer.issue();
        String tokenB = issuer.issue();

        assertThat(issuer.hash(tokenA)).isNotEqualTo(issuer.hash(tokenB));
    }

    @Test
    void expiresAtReflectsConfiguredRefreshTokenTtl() {
        Instant before = Instant.now();

        Instant expiresAt = issuer.expiresAt();

        assertThat(expiresAt).isAfter(before.plus(PROPERTIES.refreshTokenTtl()).minusSeconds(5));
        assertThat(expiresAt).isBefore(before.plus(PROPERTIES.refreshTokenTtl()).plusSeconds(5));
    }
}
