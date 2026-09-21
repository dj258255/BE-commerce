package com.beomsu.becommerce.personalization.web;

import com.beomsu.becommerce.personalization.internal.CacheBenchmark;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * E6 실험 계기 — 캐시 압축의 <b>손익 교차점</b>을 재는 벤치.
 *
 * <p>이 엔드포인트는 Redis 에 bench 키를 쓰고 지운다. 값 크기와 코덱을 바꿔 가며 같은 왕복을
 * 반복하는 것이 실험의 전부라, <b>실험 밖에서 열려 있으면 그 자체가 결함</b>이다 —
 * {@code @ConditionalOnProperty} 로 잠가 기본 프로파일에서는 빈이 만들어지지 않는다(경로가 404).
 *
 * <p>코덱은 기동 프로퍼티다(`app.personalization.cache.codec`). 코덱을 바꿀 때마다 앱을 재기동하는
 * 이유는, 코덱이 <b>저장 형식을 정하는 값</b>이라 실행 중에 바꾸면 그 사이 값의 판이 섞이기 때문이다.
 */
@RestController
@RequestMapping("/api/v1/experiments/cache")
@ConditionalOnProperty(name = "app.personalization.experiment.enabled", havingValue = "true")
public class CacheExperimentController {

    private final CacheBenchmark benchmark;

    public CacheExperimentController(CacheBenchmark benchmark) {
        this.benchmark = benchmark;
    }

    /**
     * 한 구간을 돌린다 — {@code sizeBytes} 크기의 값을 {@code count}개 넣고 같은 순서로 읽는다.
     *
     * <p>결과는 요약 통계다. 원자료(개별 왕복)는 남기지 않는다 — 이 실험의 판정은 분포의 꼬리이지
     * 개별 값이 아니다. 대신 <b>표본 수와 구간별 중앙값·백분위</b>를 함께 돌려준다.
     */
    @PostMapping("/bench")
    public Map<String, Object> bench(@RequestParam(name = "sizeBytes", defaultValue = "10240") int sizeBytes,
                                     @RequestParam(name = "count", defaultValue = "1000") int count) {
        CacheBenchmark.Result r = benchmark.run(sizeBytes, count);
        return Map.ofEntries(
                Map.entry("codec", r.codec()),
                Map.entry("thresholdBytes", r.thresholdBytes()),
                Map.entry("requestedBytes", sizeBytes),
                Map.entry("rawBytes", r.rawBytes()),
                Map.entry("count", r.count()),
                Map.entry("rawBytesTotal", r.rawBytesTotal()),
                Map.entry("storedBytesTotal", r.storedBytesTotal()),
                Map.entry("ratio", r.ratio()),
                Map.entry("setP50", r.setP50()),
                Map.entry("setP95", r.setP95()),
                Map.entry("setP99", r.setP99()),
                Map.entry("getP50", r.getP50()),
                Map.entry("getP95", r.getP95()),
                Map.entry("getP99", r.getP99()),
                Map.entry("compressP50", r.compressP50()),
                Map.entry("decompressP50", r.decompressP50()),
                Map.entry("appCpuMs", r.appCpuMs()),
                Map.entry("redisUsedMemoryBytes", r.redisUsedMemoryBytes() == null ? -1 : r.redisUsedMemoryBytes()),
                Map.entry("redisMemoryDeltaBytes", r.redisMemoryDeltaBytes() == null ? -1 : r.redisMemoryDeltaBytes()));
    }
}
