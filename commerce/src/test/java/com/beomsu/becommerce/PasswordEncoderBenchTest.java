package com.beomsu.becommerce;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * BCrypt 대 Argon2id 실측 — 알고리즘 교체의 <b>대가</b>를 숫자로 남긴다(ADR-009).
 *
 * <p>비밀번호 해싱은 느린 것이 목적이라 "빨라졌다"가 개선이 아니다. 재는 이유는 두 가지다.
 * ① 로그인 응답에 얼마가 더 붙는지 ② {@code /auth/login}의 DoS 표면이 얼마나 커지는지.
 * 단정적 수치가 아니라 <b>이 로컬에서의 상대 비교</b>다.
 */
class PasswordEncoderBenchTest {

    private static final int WARMUP = 3;
    private static final int ROUNDS = 7;

    private static long medianMillis(PasswordEncoder encoder) {
        for (int i = 0; i < WARMUP; i++) encoder.encode("benchmark-password");
        long[] took = new long[ROUNDS];
        for (int i = 0; i < ROUNDS; i++) {
            long t0 = System.nanoTime();
            encoder.encode("benchmark-password");
            took[i] = (System.nanoTime() - t0) / 1_000_000;
        }
        java.util.Arrays.sort(took);
        return took[ROUNDS / 2];
    }

    @Test
    @DisplayName("해싱 비용 실측 — 교체가 로그인에 얼마를 더 붙이는지 본다")
    void reportHashingCost() {
        long bcrypt = medianMillis(new BCryptPasswordEncoder());
        long argon2 = medianMillis(new Argon2PasswordEncoder(16, 32, 1, 19456, 2));

        System.out.printf("%n=== 비밀번호 해싱 비용 (워밍업 후 %d회 중앙값) ===%n", ROUNDS);
        System.out.printf("  BCrypt (strength 10)              : %d ms%n", bcrypt);
        System.out.printf("  Argon2id (19MiB, t=2, p=1)        : %d ms%n", argon2);
        System.out.printf("  동시 로그인 100건 시 Argon2 메모리 : 약 %d MB%n", 19 * 100);
        System.out.println("  → 메모리 하드는 서버 메모리도 쓴다. 이 경로의 유입 제한이 그래서 더 중요하다.");
    }
}
