package com.beomsu.becommerce.shared.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 블라인드 인덱스 계산기 — {@link BlindIndex}(순수 static util)에 secret을 주입해 감싼 빈.
 *
 * <p>암호화된 컬럼(빌링키)은 매번 암호문이 달라 WHERE 검색·유니크 제약이 불가능하다. 그래서 같은
 * 평문이면 항상 같은 HMAC-SHA256 값을 별도 컬럼에 저장해 동등 검색(=)과 유니크를 대체한다.
 * secret은 프로퍼티({@code app.crypto.blind-index-secret})로만 주입하고, 약하면(16바이트 미만)
 * 기동을 실패시켜 예측 가능한 인덱스로 배포되는 사고를 막는다(기존 crypto 키 fail-fast 패턴).
 */
@Component
public class BlindIndexer {

    private final String secret;

    public BlindIndexer(@Value("${app.crypto.blind-index-secret}") String secret) {
        // 약한 secret은 인덱스 역산·예측 위험을 키운다 — 기동 시점에 강도를 강제한다(fail-fast).
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 16) {
            throw new IllegalStateException(
                    "app.crypto.blind-index-secret 미설정 또는 16바이트 미만 — 시크릿을 환경변수로 주입해야 합니다.");
        }
        this.secret = secret;
    }

    /** 평문 → 결정적 HMAC-SHA256 hex(64자). 조회·유니크 키로 쓴다. */
    public String index(String value) {
        return BlindIndex.hash(value, secret);
    }
}
