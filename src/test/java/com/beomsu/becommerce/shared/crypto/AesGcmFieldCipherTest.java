package com.beomsu.becommerce.shared.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AesGcmFieldCipherTest {

    private final AesGcmFieldCipher cipher =
            new AesGcmFieldCipher("0123456789abcdef0123456789abcdef"); // 32바이트

    @Test
    @DisplayName("암호화→복호화 왕복이 원문을 복원한다")
    void roundTrip() {
        String plain = "110-1234-567890"; // 계좌번호
        String enc = cipher.encrypt(plain);
        assertThat(enc).isNotEqualTo(plain);
        assertThat(cipher.decrypt(enc)).isEqualTo(plain);
    }

    @Test
    @DisplayName("같은 평문도 매번 다른 암호문(무작위 IV)")
    void randomizedCiphertext() {
        assertThat(cipher.encrypt("same")).isNotEqualTo(cipher.encrypt("same"));
    }

    @Test
    @DisplayName("변조된 암호문은 복호화 실패(GCM 인증 태그)")
    void tamperDetected() {
        String enc = cipher.encrypt("secret");
        String tampered = enc.substring(0, enc.length() - 2) + (enc.endsWith("AA") ? "BB" : "AA");
        assertThatThrownBy(() -> cipher.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("null은 그대로 통과")
    void nullPassthrough() {
        assertThat(cipher.encrypt(null)).isNull();
        assertThat(cipher.decrypt(null)).isNull();
    }
}
