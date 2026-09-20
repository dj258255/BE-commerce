package com.beomsu.becommerce.shared.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnvelopeFieldCipherTest {

    // 테스트용 KEK 2개(각 32바이트). 실 KMS 대신 상수로 직접 구성한다(순수 JCE, 부트 없음).
    private static final SecretKey KEK_V1 = key("kek-v1-aaaaaaaaaaaaaaaaaaaaaaaaa");
    private static final SecretKey KEK_V2 = key("kek-v2-bbbbbbbbbbbbbbbbbbbbbbbbb");

    private static SecretKey key(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        assert b.length == 32 : "KEK는 32바이트여야 함: " + b.length;
        return new SecretKeySpec(b, "AES");
    }

    /** 테스트용 MasterKeyProvider — current 버전과 등록 키를 직접 지정. */
    private static MasterKeyProvider provider(String current, Map<String, SecretKey> keys) {
        return new MasterKeyProvider() {
            @Override
            public String currentVersion() {
                return current;
            }

            @Override
            public SecretKey keyFor(String version) {
                SecretKey k = keys.get(version);
                if (k == null) {
                    throw new IllegalArgumentException("알 수 없는 KEK 버전: " + version);
                }
                return k;
            }
        };
    }

    private static Map<String, SecretKey> keys(Object... pairs) {
        Map<String, SecretKey> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put((String) pairs[i], (SecretKey) pairs[i + 1]);
        }
        return m;
    }

    private final EnvelopeFieldCipher cipher =
            new EnvelopeFieldCipher(provider("v1", keys("v1", KEK_V1)));

    @Test
    @DisplayName("암호화→복호화 왕복이 원문을 복원한다")
    void roundTrip() {
        String plain = "110-1234-567890";
        String enc = cipher.encrypt(plain);
        assertThat(enc).isNotEqualTo(plain);
        assertThat(cipher.decrypt(enc)).isEqualTo(plain);
    }

    @Test
    @DisplayName("암호문은 버전 프리픽스 env:v1: 로 시작한다")
    void versionPrefix() {
        assertThat(cipher.encrypt("secret")).startsWith("env:v1:");
    }

    @Test
    @DisplayName("같은 평문도 매번 다른 암호문(무작위 DEK·IV)")
    void randomizedCiphertext() {
        assertThat(cipher.encrypt("same")).isNotEqualTo(cipher.encrypt("same"));
    }

    @Test
    @DisplayName("null은 그대로 통과")
    void nullPassthrough() {
        assertThat(cipher.encrypt(null)).isNull();
        assertThat(cipher.decrypt(null)).isNull();
    }

    @Test
    @DisplayName("로테이션: v1 암호문을 rewrapToCurrent(v2)하면 프리픽스가 env:v2:로 바뀌고 여전히 복호화된다")
    void rewrapRotatesKekWithoutReEncryptingData() {
        String plain = "계좌-777";
        String encV1 = cipher.encrypt(plain);
        String dataBlobV1 = encV1.split(":", 4)[3]; // env:v1:{wrap}:{data} 의 data 조각

        // current를 v2로 둔 provider(둘 다 등록) — 로테이션 상황.
        EnvelopeFieldCipher rotated =
                new EnvelopeFieldCipher(provider("v2", keys("v1", KEK_V1, "v2", KEK_V2)));

        String encV2 = rotated.rewrapToCurrent(encV1);
        assertThat(encV2).startsWith("env:v2:");
        // dataBlob(실제 데이터 암호문)은 재암호화하지 않고 그대로 실려 있어야 한다.
        assertThat(encV2.split(":", 4)[3]).isEqualTo(dataBlobV1);
        // 그리고 여전히 복호화되어 같은 평문이 나온다.
        assertThat(rotated.decrypt(encV2)).isEqualTo(plain);
    }

    @Test
    @DisplayName("이미 current 버전이면 rewrapToCurrent는 동일 문자열을 반환한다")
    void rewrapNoOpWhenAlreadyCurrent() {
        String enc = cipher.encrypt("x"); // current=v1
        assertThat(cipher.rewrapToCurrent(enc)).isSameAs(enc);
    }

    @Test
    @DisplayName("알 수 없는 버전의 암호문은 복호화 시 예외")
    void unknownVersionRejected() {
        // v2로 암호화한 암호문을 v1만 아는 cipher가 복호화 → keyFor(v2)에서 예외.
        EnvelopeFieldCipher v2Cipher =
                new EnvelopeFieldCipher(provider("v2", keys("v2", KEK_V2)));
        String encV2 = v2Cipher.encrypt("secret");
        assertThatThrownBy(() -> cipher.decrypt(encV2))
                .isInstanceOf(IllegalArgumentException.class);
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
    @DisplayName("레거시 평문 하위호환: env: 프리픽스가 없으면 복호화가 값을 그대로 반환한다")
    void legacyPlaintextPassthrough() {
        // 암호화 적용 이전에 저장된 평문 행을 읽을 때 예외 없이 원문을 돌려준다.
        assertThat(cipher.decrypt("110-1234-567890")).isEqualTo("110-1234-567890");
        assertThat(cipher.decrypt("not-an-envelope")).isEqualTo("not-an-envelope");
    }

    @Test
    @DisplayName("평문으로 읽힌 횟수를 센다 — 0이 돼야 하위호환 분기를 없앨 수 있다")
    void countsLegacyPlaintextReads() {
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        var cipher = new EnvelopeFieldCipher(provider("v1", keys("v1", KEK_V1)), registry, false);

        assertThat(cipher.decrypt("1234-5678")).isEqualTo("1234-5678");
        assertThat(cipher.decrypt(cipher.encrypt("secret"))).isEqualTo("secret");

        assertThat(registry.counter("crypto.legacy.plaintext.reads").count())
                .as("암호문은 세지 않고 평문만 센다")
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("이관 완료를 선언하면 평문을 통과시키지 않는다 — 우회 형식이 영구히 남지 않게")
    void rejectsLegacyPlaintextAfterMigrationDeclared() {
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        var cipher = new EnvelopeFieldCipher(provider("v1", keys("v1", KEK_V1)), registry, true);

        assertThatThrownBy(() -> cipher.decrypt("1234-5678"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("평문");

        assertThat(cipher.decrypt(cipher.encrypt("secret")))
                .as("정상 암호문은 그대로 읽힌다")
                .isEqualTo("secret");
    }
}