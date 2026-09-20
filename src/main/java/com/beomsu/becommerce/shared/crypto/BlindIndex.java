package com.beomsu.becommerce.shared.crypto;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * 블라인드 인덱스 — 암호화된 컬럼을 동등 검색하기 위한 HMAC 해시.
 *
 * <p>필드를 AES-GCM으로 암호화하면 매번 암호문이 달라 WHERE 검색이 불가능하다. 그래서 같은 평문이
 * 항상 같은 해시가 되는 HMAC-SHA256 값을 별도 컬럼에 저장해 동등 검색(=)만 허용한다.
 * (범위 검색은 불가 — 그건 암호화 컬럼의 본질적 한계다.)
 */
public final class BlindIndex {

    private BlindIndex() {
    }

    public static String hash(String value, String secret) {
        if (value == null) {
            return null;
        }
        // 약한 secret은 해시 예측·역산 위험을 키운다 — 최소 강도를 강제한다.
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 16) {
            throw new IllegalArgumentException("블라인드 인덱스 secret은 16바이트 이상이어야 합니다.");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("블라인드 인덱스 계산 실패", e);
        }
    }
}
