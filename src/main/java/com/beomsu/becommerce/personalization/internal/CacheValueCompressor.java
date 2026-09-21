package com.beomsu.becommerce.personalization.internal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 캐시 값을 <b>임계값에 따라</b> 압축해 저장하고 읽는다 — E6(M6)의 결정이 여기 꽂힌다.
 *
 * <p><b>임계값이 필요한 이유</b>: 압축은 공짜가 아니다 — 헤더·표식·CPU 를 쓰고, 값이 작으면
 * 압축 결과가 원본보다 커지기도 한다. 그래서 "몇 바이트를 넘을 때부터 압축하는가"를 정하고,
 * <b>그 아래에서는 원본을 그대로 둔다</b>. E6 는 그 교차점을 같은 하네스로 재는 실험이고,
 * 이 클래스는 그 결정을 실제 저장 경로에 적용하는 자리다.
 *
 * <p><b>기본값이 {@code NONE} 인 이유</b>: 아직 측정 전이거나 임계값 이하라는 뜻이 아니라,
 * <b>압축이 이득이라는 근거가 아직 없다</b>는 뜻이다. E5 에서 생성 범위를 켜지 않은 것과 같은 규칙 —
 * 근거 없이 비용을 쓰지 않는다.
 *
 * <p><b>표식(marker)</b>: 저장된 값이 압축됐는지 길이로는 알 수 없다(작은 값은 압축해도 커진다).
 * 그래서 코덱별 접두어를 붙이고, 그 바이트도 <b>저장량에 포함</b>해 계산한다.
 */
@Component
public class CacheValueCompressor {

    private final CacheValueCodec codec;

    /** 이 바이트 수 <b>이상</b>이면 압축한다. 0 이면 (코덱이 NONE 이 아닐 때) 항상 압축한다. */
    private final int thresholdBytes;

    public CacheValueCompressor(@Value("${app.personalization.cache.codec:NONE}") CacheValueCodec codec,
                                @Value("${app.personalization.cache.compress-threshold-bytes:0}") int thresholdBytes) {
        this.codec = codec;
        this.thresholdBytes = Math.max(thresholdBytes, 0);
    }

    /** 지금 켜진 코덱 — 응답·리포트가 "무엇으로 돌았는가"를 남기기 위해 쓴다. */
    public CacheValueCodec codec() {
        return codec;
    }

    public int thresholdBytes() {
        return thresholdBytes;
    }

    /**
     * 저장할 문자열을 만든다. 임계값 미만이거나 코덱이 {@code NONE} 이면 <b>원본 그대로</b>다
     * (압축했다가 풀어도 같은 바이트를 두 번 쓰는 셈이라, 아끼는 것 없이 위험만 는다).
     */
    public String store(String value) {
        byte[] raw = value.getBytes(StandardCharsets.UTF_8);
        if (codec == CacheValueCodec.NONE || raw.length < thresholdBytes) {
            return value;
        }
        byte[] packed = codec.compress(raw);
        return codec.marker() + Base64.getEncoder().encodeToString(packed);
    }

    /** 저장된 값을 되돌린다. 표식이 없으면 원본 JSON 이다. */
    public String load(String stored) {
        for (CacheValueCodec candidate : CacheValueCodec.values()) {
            String marker = candidate.marker();
            if (!marker.isEmpty() && stored.startsWith(marker)) {
                byte[] packed = Base64.getDecoder().decode(stored.substring(marker.length()));
                return new String(candidate.decompress(packed), StandardCharsets.UTF_8);
            }
        }
        return stored;
    }

    /** 실제로 저장되는 바이트 수 — base64 팽창과 표식까지 포함한 값이다(이게 청구서다). */
    public static long storedBytes(String stored) {
        return stored.getBytes(StandardCharsets.UTF_8).length;
    }
}
