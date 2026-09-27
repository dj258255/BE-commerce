package com.beomsu.becommerce.reconciliation.integrity;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 정합성 점검을 주기적으로 돌려 {@code integrity.violations{invariant}} 게이지로 낸다(#389).
 *
 * <p>다른 배치와 같이 {@code app.integrity.enabled} 로 켜고 {@link IntegritySchedulingConfig} 가 짝이다. 게이지도 켤 때만
 * 등록한다. 꺼져 있는데 0 을 내보내면 "점검하지 않음"과 "위반 없음"이 같은 모양이 된다. 같은 이유로 점검이 실패하면
 * 게이지를 0 으로 되돌리지 않고 직전 값에 둔다.
 *
 * <p>게이지 supplier 가 스크레이프마다 조인을 돌지 않도록 셈은 스케줄러가 하고 게이지는 마지막 값을 읽는다.
 */
@Component
@ConditionalOnProperty(name = "app.integrity.enabled", havingValue = "true")
public class IntegrityCheckScheduler {

    private static final Logger log = LoggerFactory.getLogger(IntegrityCheckScheduler.class);

    private final IntegrityCheckService service;
    private final Map<Invariant, AtomicLong> last = new EnumMap<>(Invariant.class);

    IntegrityCheckScheduler(IntegrityCheckService service, MeterRegistry meterRegistry) {
        this.service = service;
        for (Invariant inv : Invariant.values()) {
            AtomicLong holder = new AtomicLong();
            last.put(inv, holder);
            Gauge.builder("integrity.violations", holder, AtomicLong::get)
                    .tag("invariant", inv.name())
                    .description("유예보다 오래된 건 중 이 불변식을 어긴 건수(마지막 점검 값)")
                    .register(meterRegistry);
        }
    }

    @Scheduled(fixedDelayString = "${app.integrity.check-interval-ms:60000}",
            initialDelayString = "${app.integrity.initial-delay-ms:60000}")
    void refresh() {
        try {
            Map<Invariant, Long> counts = service.count(service.defaultGrace());
            counts.forEach((inv, n) -> last.get(inv).set(n));
            long total = counts.values().stream().mapToLong(Long::longValue).sum();
            if (total > 0) {
                log.warn("정합성 불변식 위반 {}건: {}", total, counts);
            }
        } catch (RuntimeException e) {
            log.error("정합성 점검 실패, 게이지는 직전 값을 유지한다", e);
        }
    }
}
