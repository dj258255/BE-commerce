package com.beomsu.becommerce.auth.internal;

import com.beomsu.becommerce.auth.TokenStore;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * per-request 폐기 검사 — 기본 validator(만료·서명) 뒤에 붙어, denylist에 오른 access 토큰의
 * {@code jti}를 거부한다. 무상태 JWT의 "발급 후엔 못 막는다"는 한계를 {@link TokenStore} 조회로 메운다.
 *
 * <p>jti가 없는 구토큰은 폐기 대상이 될 수 없으므로 통과(널가드)한다. Redis 장애 시엔
 * {@link TokenStore#isRevoked}가 fail-open(false)이라 여기서도 통과된다 — 가용성 우선.
 */
class RevocationValidator implements OAuth2TokenValidator<Jwt> {

    private final TokenStore tokenStore;

    RevocationValidator(TokenStore tokenStore) {
        this.tokenStore = tokenStore;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        String jti = jwt.getId();
        if (jti == null) {
            return OAuth2TokenValidatorResult.success();   // 구토큰(jti 없음) — 폐기 검사 skip
        }
        if (tokenStore.isRevoked(jti)) {
            return OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "폐기된 토큰입니다.", null));
        }
        return OAuth2TokenValidatorResult.success();
    }
}
