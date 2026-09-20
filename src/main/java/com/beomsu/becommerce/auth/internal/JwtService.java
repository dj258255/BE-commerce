package com.beomsu.becommerce.auth.internal;

import com.beomsu.becommerce.auth.TokenStore;
import com.beomsu.becommerce.SecurityConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * 액세스 토큰(JWT) 발급 + refresh id 생성.
 *
 * <p>subject에 userId(데모 유저의 username "1" 등)를 넣어, 검증 후 {@code principal.getName()}이
 * 계속 userId를 반환하도록 한다 — 기존 주문 소유권 검증(IDOR 방지)이 무수정으로 동작한다.
 * roles 클레임은 {@code SecurityConfig}의 컨버터와 정확히 맞물린다(예: {@code ["ROLE_USER"]} →
 * prefix 없이 그대로 authority가 되어 {@code hasRole("USER")}와 매칭).
 *
 * <p>access 토큰에는 {@code jti}(고유 식별자)를 실어, 로그아웃/탈취 시 {@link TokenStore} denylist로
 * 개별 폐기할 수 있게 한다. refresh는 JWT가 아니라 opaque UUID다({@link #newRefreshId}) —
 * Redis 조회로만 검증되므로 서버가 언제든 회전·폐기할 수 있다.
 */
@Service
public class JwtService {

    private final JwtEncoder encoder;
    private final long accessExpiryMinutes;
    private final long refreshExpiryDays;

    public JwtService(JwtEncoder encoder,
                      @Value("${app.jwt.access-expiry-minutes:30}") long accessExpiryMinutes,
                      @Value("${app.jwt.refresh-expiry-days:14}") long refreshExpiryDays) {
        this.encoder = encoder;
        this.accessExpiryMinutes = accessExpiryMinutes;
        this.refreshExpiryDays = refreshExpiryDays;
    }

    /**
     * access 토큰 발급. jti(UUID)를 포함해 개별 폐기가 가능하다.
     *
     * @param userId subject로 저장(검증 후 principal name = userId)
     * @param roles  권한 클레임. "ROLE_USER"/"ROLE_ADMIN" 형태로 그대로 저장한다.
     */
    public String issueAccess(String userId, Collection<String> roles) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("be-commerce")
                .id(UUID.randomUUID().toString())   // jti — denylist 폐기의 키
                .subject(userId)
                .issuedAt(now)
                .expiresAt(now.plus(accessExpiryMinutes, ChronoUnit.MINUTES))
                .claim("roles", List.copyOf(roles))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    /** opaque refresh 토큰(UUID). JWT가 아니며 {@link TokenStore} 조회로만 검증된다. */
    public String newRefreshId() {
        return UUID.randomUUID().toString();
    }

    /** access 만료까지의 초 — 로그인 응답의 {@code expiresInSeconds}로 노출된다. */
    public long expirySeconds() {
        return accessExpiryMinutes * 60;
    }

    /** refresh 토큰의 Redis TTL. */
    public Duration refreshTtl() {
        return Duration.ofDays(refreshExpiryDays);
    }
}
