package com.beomsu.becommerce.shared.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BlindIndexerTest {

    private static final String SECRET = "blind-index-secret-key-0000000000";
    private static final String OTHER_SECRET = "another-blind-index-secret-000000";

    @Test
    @DisplayName("같은 입력은 같은 인덱스 — 결정적이라 동등 조회·유니크가 가능하다")
    void deterministic() {
        BlindIndexer indexer = new BlindIndexer(SECRET);
        assertThat(indexer.index("billing-key-abc")).isEqualTo(indexer.index("billing-key-abc"));
    }

    @Test
    @DisplayName("인덱스는 64자 hex(HMAC-SHA256)")
    void hexLength() {
        assertThat(new BlindIndexer(SECRET).index("x")).hasSize(64).matches("[0-9a-f]+");
    }

    @Test
    @DisplayName("secret이 다르면 같은 입력도 다른 인덱스 — secret이 인덱스 예측을 막는다")
    void differentSecretDiffers() {
        assertThat(new BlindIndexer(SECRET).index("same"))
                .isNotEqualTo(new BlindIndexer(OTHER_SECRET).index("same"));
    }

    @Test
    @DisplayName("약한 secret(16바이트 미만)은 기동 실패(fail-fast)")
    void weakSecretFailsFast() {
        assertThatThrownBy(() -> new BlindIndexer("short"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new BlindIndexer(null))
                .isInstanceOf(IllegalStateException.class);
    }
}
