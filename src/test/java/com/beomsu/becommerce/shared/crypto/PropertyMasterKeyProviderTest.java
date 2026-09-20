package com.beomsu.becommerce.shared.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PropertyMasterKeyProviderTest {

    private static final String V1 = "kek-v1-aaaaaaaaaaaaaaaaaaaaaaaaa"; // 32바이트
    private static final String V2 = "kek-v2-bbbbbbbbbbbbbbbbbbbbbbbbb"; // 32바이트

    @Test
    @DisplayName("current/keyFor — v1만 등록하면 v1이 current이고 keyFor(v1)이 나온다")
    void currentAndKeyFor() {
        PropertyMasterKeyProvider p = new PropertyMasterKeyProvider("v1", V1, "");
        assertThat(p.currentVersion()).isEqualTo("v1");
        assertThat(p.keyFor("v1")).isNotNull();
    }

    @Test
    @DisplayName("v2가 있으면 옵션 등록되고 current를 v2로 올릴 수 있다")
    void v2OptionalRegistered() {
        PropertyMasterKeyProvider p = new PropertyMasterKeyProvider("v2", V1, V2);
        assertThat(p.currentVersion()).isEqualTo("v2");
        assertThat(p.keyFor("v1")).isNotNull();
        assertThat(p.keyFor("v2")).isNotNull();
    }

    @Test
    @DisplayName("v1 미설정이면 fail-fast(공개 기본키 배포 사고 차단)")
    void v1RequiredFailFast() {
        assertThatThrownBy(() -> new PropertyMasterKeyProvider("v1", "", ""))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("v1 길이 오류(32바이트 아님)면 fail-fast")
    void v1LengthValidated() {
        assertThatThrownBy(() -> new PropertyMasterKeyProvider("v1", "too-short", ""))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("current가 등록되지 않은 버전이면 fail-fast")
    void currentMustBeRegistered() {
        assertThatThrownBy(() -> new PropertyMasterKeyProvider("v2", V1, "")) // v2 미등록
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("keyFor에 없는 버전을 주면 예외")
    void keyForUnknownVersion() {
        PropertyMasterKeyProvider p = new PropertyMasterKeyProvider("v1", V1, "");
        assertThatThrownBy(() -> p.keyFor("v9"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
