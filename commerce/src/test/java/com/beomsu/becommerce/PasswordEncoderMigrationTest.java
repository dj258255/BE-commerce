package com.beomsu.becommerce;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 비밀번호 해시 알고리즘 교체의 <b>무중단 조건</b>을 고정한다(ADR-009).
 *
 * <p>새로 만드는 해시는 Argon2id로 가되, <b>이미 저장된 해시는 전부 계속 검증돼야 한다.</b>
 * 하나라도 깨지면 그 사용자는 비밀번호를 재설정하지 않는 한 로그인할 수 없다. 그래서 세 세대의
 * 해시(접두사 없는 레거시 BCrypt / {bcrypt} / {argon2})를 모두 검증한다.
 */
class PasswordEncoderMigrationTest {

    /** 프로덕션(SecurityConfig)과 같은 구성. */
    private static PasswordEncoder productionEncoder() {
        PasswordEncoder argon2 = new Argon2PasswordEncoder(16, 32, 1, 19456, 2);
        PasswordEncoder bcrypt = new BCryptPasswordEncoder();
        DelegatingPasswordEncoder d = new DelegatingPasswordEncoder(
                "argon2", Map.of("argon2", argon2, "bcrypt", bcrypt));
        d.setDefaultPasswordEncoderForMatches(bcrypt);
        return d;
    }

    @Test
    @DisplayName("새로 만드는 해시는 Argon2id다 — 접두사로 확인한다")
    void newHashesUseArgon2() {
        String hash = productionEncoder().encode("pw-1234");

        assertThat(hash).startsWith("{argon2}");
    }

    @Test
    @DisplayName("접두사 없는 레거시 BCrypt 해시도 계속 검증된다 — 기존 회원이 잠기지 않는다")
    void legacyUnprefixedBcryptStillMatches() {
        String legacy = new BCryptPasswordEncoder().encode("pw-1234");
        assertThat(legacy).doesNotStartWith("{");

        assertThat(productionEncoder().matches("pw-1234", legacy)).isTrue();
        assertThat(productionEncoder().matches("wrong", legacy)).isFalse();
    }

    @Test
    @DisplayName("{bcrypt} 접두사가 붙은 해시도 계속 검증된다 — 이관 중간 세대")
    void prefixedBcryptStillMatches() {
        String prefixed = "{bcrypt}" + new BCryptPasswordEncoder().encode("pw-1234");

        assertThat(productionEncoder().matches("pw-1234", prefixed)).isTrue();
    }

    @Test
    @DisplayName("Argon2로 만든 해시는 당연히 검증된다 — 왕복이 성립한다")
    void argon2RoundTrips() {
        PasswordEncoder enc = productionEncoder();
        String hash = enc.encode("pw-1234");

        assertThat(enc.matches("pw-1234", hash)).isTrue();
        assertThat(enc.matches("pw-12345", hash)).isFalse();
    }

    @Test
    @DisplayName("BCrypt는 72바이트에서 잘리지만 Argon2id는 안 잘린다 — 교체 이유 중 하나를 고정한다")
    void argon2DoesNotTruncateLongPasswords() {
        String base = "a".repeat(72);
        String longer = base + "DIFFERENT-TAIL";

        // BCrypt: 72바이트까지만 보므로 뒤가 달라도 같은 것으로 판정한다.
        BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();
        assertThat(bcrypt.matches(longer, bcrypt.encode(base))).isTrue();

        // Argon2id: 길이 제한이 없어 뒤가 다르면 다른 비밀번호다.
        PasswordEncoder enc = productionEncoder();
        assertThat(enc.matches(longer, enc.encode(base))).isFalse();
    }
}
