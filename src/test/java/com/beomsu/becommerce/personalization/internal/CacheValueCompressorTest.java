package com.beomsu.becommerce.personalization.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 캐시 압축의 <b>계약</b>을 고정한다 — E6의 결론이 코드에 제대로 꽂히는가.
 *
 * <p>보는 것 셋:
 * <ul>
 *   <li><b>임계값 아래에서는 원본 그대로</b>다. 압축했다 풀었다 해도 같은 바이트를 두 번 쓰는 셈이라,
 *       작은 값에서는 아끼는 것이 없이 위험만 는다</li>
 *   <li><b>왕복이 값을 바꾸지 않는다.</b> 압축은 손실이 없어야 한다 — 캐시가 값을 조용히 바꾸면
 *       그 다음이 전부 틀린다</li>
 *   <li><b>작은 값에서는 압축이 손해</b>다. 이것이 E6의 가설이고, 코드가 그 사실을 숨기지 않는지 본다</li>
 * </ul>
 */
class CacheValueCompressorTest {

    private static final String SMALL = "{\"seq\":1,\"itemId\":42}";

    /** 압축이 잘 되는 값(반복이 많다)과 안 되는 값(무작위)을 둘 다 쓴다 — 압축률은 값의 성질이다. */
    private static String repetitive(int size) {
        return "{\"pad\":\"" + "ab".repeat(size / 2) + "\"}";
    }

    private static String random(int size) {
        Random random = new Random(42);
        StringBuilder sb = new StringBuilder(size);
        while (sb.length() < size) {
            sb.append((char) ('a' + random.nextInt(26)));
        }
        return sb.toString();
    }

    @Test
    @DisplayName("코덱이 NONE이면 임계값과 무관하게 원본 그대로다")
    void noneNeverCompresses() {
        CacheValueCompressor compressor = new CacheValueCompressor(CacheValueCodec.NONE, 0);
        String stored = compressor.store(repetitive(4096));

        assertThat(stored).isEqualTo(repetitive(4096));
        assertThat(compressor.load(stored)).isEqualTo(repetitive(4096));
    }

    @Test
    @DisplayName("임계값 아래 값은 압축하지 않는다 — 아끼는 것이 없이 위험만 는다")
    void belowThresholdStaysRaw() {
        CacheValueCompressor compressor = new CacheValueCompressor(CacheValueCodec.LZ4, 4096);

        assertThat(compressor.store(SMALL))
                .as("임계값 미만이면 표식도 붙지 않는다")
                .isEqualTo(SMALL);
    }

    @Test
    @DisplayName("임계값 이상이면 압축하고, 표식이 붙는다 — 표식이 없으면 읽을 때 구분할 수 없다")
    void aboveThresholdCompresses() {
        CacheValueCompressor compressor = new CacheValueCompressor(CacheValueCodec.LZ4, 64);
        String value = repetitive(4096);

        String stored = compressor.store(value);

        assertThat(stored).startsWith("lz4:");
        assertThat(compressor.load(stored)).isEqualTo(value);
    }

    @Test
    @DisplayName("두 코덱 모두 왕복이 값을 바꾸지 않는다 — 캐시가 값을 조용히 바꾸면 그 다음이 전부 틀린다")
    void roundTripIsLosslessForBothCodecs() {
        for (CacheValueCodec codec : new CacheValueCodec[]{CacheValueCodec.LZ4, CacheValueCodec.SNAPPY}) {
            CacheValueCompressor compressor = new CacheValueCompressor(codec, 0);
            for (String value : new String[]{SMALL, repetitive(10_000), random(5_000), "한글 값 ✓"}) {
                assertThat(compressor.load(compressor.store(value)))
                        .as("코덱 %s", codec)
                        .isEqualTo(value);
            }
        }
    }

    @Test
    @DisplayName("작은 값에서는 압축이 손해다 — 저장량이 원본보다 커진다(base64 팽창 33% 포함)")
    void compressionIsALossForSmallValues() {
        CacheValueCompressor compressor = new CacheValueCompressor(CacheValueCodec.LZ4, 0);
        String stored = compressor.store(SMALL);

        assertThat(CacheValueCompressor.storedBytes(stored))
                .as("표식 + base64 가 원본보다 크다 — E6 가 이 교차점을 찾는 실험이다")
                .isGreaterThan(SMALL.getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    @DisplayName("큰 반복 값에서는 압축이 이긴다 — 저장량이 원본보다 작아진다")
    void compressionWinsForLargeRepetitiveValues() {
        CacheValueCompressor compressor = new CacheValueCompressor(CacheValueCodec.LZ4, 0);
        String value = repetitive(10_000);
        String stored = compressor.store(value);

        assertThat(CacheValueCompressor.storedBytes(stored))
                .isLessThan(value.getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    @DisplayName("표식 없는 값은 원본으로 읽는다 — 옛 값(압축 전)과 섞여 있어도 안전하다")
    void rawValueWithoutMarkerIsReadAsIs() {
        CacheValueCompressor compressor = new CacheValueCompressor(CacheValueCodec.SNAPPY, 0);

        assertThat(compressor.load("{\"items\":[]}")).isEqualTo("{\"items\":[]}");
    }
}
