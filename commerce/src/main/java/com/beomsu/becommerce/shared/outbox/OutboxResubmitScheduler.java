package com.beomsu.becommerce.shared.outbox;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 미완료 이벤트 발행(event_publication) <b>상시 재제출</b> 스케줄러.
 *
 * <p>Modulith 아웃박스에서 리스너가 실패해 남은 미완료 발행은, 지금은
 * {@code republish-outstanding-events-on-restart=true} 로 <b>앱을 재기동할 때만</b> 재제출된다.
 * 그래서 앱이 오래 떠 있으면 실패한 발행이 <b>재기동 전까지 영영 안 풀린다</b>. 이 스케줄러가
 * 떠 있는 동안 주기적으로 다시 제출한다. {@code app.outbox.resubmit.enabled=true} 일 때만 빈으로
 * 등록된다({@link OutboxResubmitSchedulingConfig} 가 같은 프로퍼티로 @EnableScheduling 을 켠다).
 *
 * <p><b>한계 — 포기(dead-letter) 개념이 없다.</b> 리스너가 계속 실패하는 이벤트는 매 주기 다시
 * 시도된다. 여기서 그 이벤트를 <b>버리는</b> 코드는 없다(운영자가 원인을 고치면 다음 틱에 풀린다).
 * 그래서 비용을 묶는 장치는 둘뿐이다:
 * <ul>
 *   <li>{@code min-age}(기본 {@code PT5M}) — 이 나이보다 어린 미완료는 건드리지 않는다. 지금 막
 *       발행돼 리스너가 처리 중일 수 있는 건을 다시 쏘지 않기 위한 최소 나이다.</li>
 *   <li>{@code max-per-tick}(기본 100) — 한 틱에 재제출하는 건수 상한. 리스너가 계속 실패하는
 *       이벤트가 매 틱 무한히 다시 도는 폭주를 묶는다.</li>
 * </ul>
 * 두 값을 낮추면 폭주는 줄지만 실패한 발행이 풀리기까지 더 오래 걸린다 — 그 맞바꿈이 이 스케줄러의
 * 유일한 조절 손잡이다.
 *
 * <p>재제출 대상은 {@link IncompleteEventPublications}(= 미완료)뿐이다. 완료(completion-mode=archive)
 * 된 발행은 애초에 여기 오지 않으므로 다시 나가지 않는다.
 */
@Component
@ConditionalOnProperty(name = "app.outbox.resubmit.enabled", havingValue = "true")
class OutboxResubmitScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxResubmitScheduler.class);

    private final IncompleteEventPublications incompleteEventPublications;
    private final MeterRegistry meterRegistry;
    private final Duration minAge;
    private final int maxPerTick;

    OutboxResubmitScheduler(IncompleteEventPublications incompleteEventPublications,
                            MeterRegistry meterRegistry,
                            @Value("${app.outbox.resubmit.min-age:PT5M}") Duration minAge,
                            @Value("${app.outbox.resubmit.max-per-tick:100}") int maxPerTick) {
        this.incompleteEventPublications = incompleteEventPublications;
        this.meterRegistry = meterRegistry;
        this.minAge = minAge;
        this.maxPerTick = maxPerTick;
    }

    @Scheduled(fixedDelayString = "${app.outbox.resubmit.interval-ms:60000}")
    public void run() {
        Instant cutoff = Instant.now().minus(minAge);
        AtomicInteger remaining = new AtomicInteger(maxPerTick);
        AtomicInteger resubmitted = new AtomicInteger();
        incompleteEventPublications.resubmitIncompletePublications(publication -> {
            // 완료된 발행은 IncompleteEventPublications 에 애초에 오지 않지만, 경계를 코드로 못박아 둔다.
            if (publication.getCompletionDate().isPresent()) {
                return false;
            }
            // 방금 발행돼 리스너가 아직 처리 중일 수 있는 건은 건드리지 않는다(min-age).
            if (!publication.getPublicationDate().isBefore(cutoff)) {
                return false;
            }
            // 한 틱 상한 — 계속 실패하는 이벤트가 매 틱 끝없이 다시 도는 것을 묶는다.
            if (remaining.getAndDecrement() <= 0) {
                return false;
            }
            resubmitted.incrementAndGet();
            return true;
        });
        int count = resubmitted.get();
        if (count > 0) {
            meterRegistry.counter("outbox.resubmitted").increment(count);
            log.info("Outbox 미완료 이벤트 재제출 count={} minAge={} maxPerTick={}", count, minAge, maxPerTick);
        }
    }
}
