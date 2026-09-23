package com.beomsu.becommerce.auth.internal;

import com.beomsu.becommerce.auth.TokenStore;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * JWT(HS256) 서명 키와 인코더/디코더 구성.
 *
 * <p>자체 발급/검증 — 외부 IdP나 issuer-uri 없이 로컬 대칭 비밀키로 토큰을 발급하고 검증한다.
 * 로그인 시 1회만 BCrypt로 자격증명을 확인하고, 이후 요청은 이 대칭키로 서명만 검증하므로
 * 요청당 재해싱 비용이 사라진다.
 *
 * <p>키는 코드에 두지 않고 {@code app.jwt.secret}(설정/환경변수)로 주입한다. HS256은 최소
 * 32바이트(256비트)를 요구하므로, 그보다 짧으면 기동을 실패시켜(fail-fast) 약한 키로 배포되는
 * 사고를 막는다({@link com.beomsu.becommerce.shared.crypto.AesGcmFieldCipher}와 같은 결).
 */
@Configuration
public class JwtConfig {

    private final SecretKey secretKey;

    public JwtConfig(@Value("${app.jwt.secret}") String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "app.jwt.secret 미설정 — JWT 서명 키를 환경변수/시크릿으로 주입해야 합니다.");
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException(
                    "HS256 서명 키는 최소 32바이트(256비트)여야 합니다(현재 " + bytes.length + ").");
        }
        this.secretKey = new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder() {
        return new NimbusJwtEncoder(new ImmutableSecret<>(secretKey));
    }

    /**
     * 디코더는 기본 validator(만료·서명)에 더해 {@link RevocationValidator}(denylist 폐기 검사)를
     * per-request로 실행한다. {@link TokenStore}는 {@code StringRedisTemplate}만 의존하므로
     * (JwtConfig ← TokenStore ← RedisTemplate) 순환이 없다. 빈 메서드 파라미터로 주입받아
     * 단위 테스트에서 목 TokenStore로 손쉽게 대체할 수 있게 한다.
     */
    @Bean
    JwtDecoder jwtDecoder(TokenStore tokenStore) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(secretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefault(),          // 만료·nbf 등 기본 검증
                new RevocationValidator(tokenStore)));   // + denylist 폐기 검사
        return decoder;
    }
}
