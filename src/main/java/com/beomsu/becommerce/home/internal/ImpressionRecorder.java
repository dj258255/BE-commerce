package com.beomsu.becommerce.home.internal;

import com.beomsu.becommerce.home.HomePageView;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 홈 노출 기록기 — <b>무엇이 화면에 나갔는가</b>를 남긴다(M7).
 *
 * <p><b>실패해도 홈을 죽이지 않는다.</b> 홈은 제품이고 노출 기록은 관측이다 — 기록이 안 된다고 상점 첫
 * 화면이 500 을 내면 우선순위가 뒤집힌다. 대신 <b>삼키지 않고</b> 경고 로그와 지표로 남긴다
 * ({@code home.impression.failed}). 조용히 사라지면 "기록이 없다"와 "기록이 실패했다"를 구분할 수 없다.
 *
 * <p><b>요청당 한 번 쓴다</b>(항목당이 아니다). 그 판단과 대가는 ADR-043.
 */
@Component
public class ImpressionRecorder {

    private static final Logger log = LoggerFactory.getLogger(ImpressionRecorder.class);

    private final HomeImpressionRepository repository;
    private final Counter logged;
    private final Counter failed;
    private final boolean enabled;

    public ImpressionRecorder(HomeImpressionRepository repository, MeterRegistry registry,
                              @Value("${app.home.impression-log.enabled:true}") boolean enabled) {
        this.repository = repository;
        this.enabled = enabled;
        this.logged = Counter.builder("home.impression.logged")
                .description("노출 기록을 남긴 홈 응답 수")
                .register(registry);
        this.failed = Counter.builder("home.impression.failed")
                .description("노출 기록에 실패한 수 — 0 이 아니면 저장소를 봐야 한다")
                .register(registry);
        log.info("홈 노출 기록={} (요청당 한 행)", enabled ? "켜짐" : "꺼짐");
    }

    /** 홈 응답 하나를 기록한다. 실패는 경고로 남기고 <b>예외를 올리지 않는다</b>. */
    public void record(HomePageView page) {
        if (!enabled) {
            return;
        }
        try {
            repository.save(HomeImpression.of(page));
            logged.increment();
        } catch (RuntimeException e) {
            failed.increment();
            log.warn("노출 기록 실패 — 홈은 그대로 나간다. userId={} cause={}", page.userId(), e.toString());
        }
    }
}
