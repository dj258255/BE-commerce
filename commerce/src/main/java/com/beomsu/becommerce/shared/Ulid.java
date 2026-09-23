package com.beomsu.becommerce.shared;

import java.security.SecureRandom;
import java.time.Instant;

/**
 * ULID 생성기 (Crockford Base32, 26자).
 *
 * <p>외부에 노출되는 식별자(주문번호 등)에 자동증가 PK 대신 사용한다. 이유:
 * <ul>
 *   <li>순회 공격 방지 — 연속된 주문 ID로 남의 주문을 추측할 수 없다.</li>
 *   <li>시간 정렬 가능 — 앞 48비트가 밀리초 타임스탬프라 UUID보다 인덱스에 우호적이다.</li>
 * </ul>
 * 내부 조인 키는 여전히 BIGINT PK를 쓰고, 외부 식별만 ULID로 분리한다.
 */
public final class Ulid {

    private static final char[] ENCODING = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private Ulid() {
    }

    public static String generate() {
        return generate(Instant.now().toEpochMilli());
    }

    static String generate(long timestamp) {
        char[] out = new char[26];
        // 앞 10자 = 48비트 타임스탬프
        for (int i = 9; i >= 0; i--) {
            out[i] = ENCODING[(int) (timestamp & 0x1F)];
            timestamp >>>= 5;
        }
        // 뒤 16자 = 80비트 무작위
        for (int i = 10; i < 26; i++) {
            out[i] = ENCODING[RANDOM.nextInt(32)];
        }
        return new String(out);
    }
}
