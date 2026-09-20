package com.beomsu.becommerce.auth.internal;

import com.beomsu.becommerce.auth.TokenStore;
import com.beomsu.becommerce.auth.internal.JwtService;
import com.beomsu.becommerce.auth.internal.JwtConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * JWT 발급/검증 단위 테스트 — JwtConfig의 인코더/디코더를 직접 생성해 왕복을 확인한다.
 * (Spring 컨텍스트 로딩 없이 순수 단위. TokenStore는 목 — 폐기 없음으로 고정.)
 */
class JwtServiceTest {

    // 32바이트 이상 테스트 시크릿(HS256 최소 길이 충족)
    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    private final JwtConfig config = new JwtConfig(SECRET);
    private final JwtService service = new JwtService(config.jwtEncoder(), 30, 14);
    private final TokenStore tokenStore = mock(TokenStore.class);
    private final JwtDecoder decoder = config.jwtDecoder(tokenStore);

    @Test
    @DisplayName("발급한 access 토큰을 디코드하면 jti, subject=userId, roles, 미래 만료가 담겨 있다")
    void issuedTokenDecodesWithClaims() {
        when(tokenStore.isRevoked(org.mockito.ArgumentMatchers.anyString())).thenReturn(false);
        String token = service.issueAccess("1", List.of("ROLE_USER"));

        Jwt jwt = decoder.decode(token);
        assertThat(jwt.getId()).isNotBlank();                              // jti — 폐기의 키
        assertThat(jwt.getSubject()).isEqualTo("1");                       // principal name = userId
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("ROLE_USER");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("be-commerce");
        assertThat(jwt.getExpiresAt()).isAfter(Instant.now());
    }

    @Test
    @DisplayName("폐기된 jti의 토큰은 디코드(검증) 시 거부된다")
    void revokedTokenIsRejected() {
        when(tokenStore.isRevoked(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        String token = service.issueAccess("1", List.of("ROLE_USER"));

        assertThatThrownBy(() -> decoder.decode(token))
                .isInstanceOf(org.springframework.security.oauth2.jwt.JwtValidationException.class);
    }

    @Test
    @DisplayName("newRefreshId는 매번 다른 UUID를 반환한다")
    void refreshIdIsUnique() {
        assertThat(service.newRefreshId()).isNotEqualTo(service.newRefreshId());
    }

    @Test
    @DisplayName("시크릿이 32바이트 미만이면 기동 실패(fail-fast)")
    void shortSecretFailsFast() {
        assertThatThrownBy(() -> new JwtConfig("too-short-secret"))   // < 32바이트
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("빈 시크릿도 기동 실패(fail-fast)")
    void blankSecretFailsFast() {
        assertThatThrownBy(() -> new JwtConfig("   "))
                .isInstanceOf(IllegalStateException.class);
    }
}
