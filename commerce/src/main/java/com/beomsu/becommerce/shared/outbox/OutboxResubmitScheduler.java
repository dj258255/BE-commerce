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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
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
 * 그래서 비용을 묶는 장치는 셋이다:
 * <ul>
 *   <li>{@code min-age}(기본 {@code PT5M}) — 이 나이보다 어린 미완료는 건드리지 않는다. 지금 막
 *       발행돼 리스너가 처리 중일 수 있는 건을 다시 쏘지 않기 위한 최소 나이다.</li>
 *   <li>{@code max-per-tick}(기본 100) — 한 틱에 재제출하는 건수 상한. 리스너가 계속 실패하는
 *       이벤트가 매 틱 무한히 다시 도는 폭주를 묶는다.</li>
 *   <li>{@code cooldown}(기본 {@code PT10M}) — <b>선두 막힘 완화.</b> 미완료를 오래된 순으로 읽으면,
 *       계속 실패하는 독약 이벤트가 상한 이상 쌓였을 때 매 틱 그 건들만 재제출돼 <b>그 뒤의 정상 실패
 *       건이 영영 차례를 못 받는다</b>. 같은 발행을 재제출한 뒤 쿨다운 동안은 predicate 에서 빼
 *       그만큼 상한 슬롯이 뒤의 건에게 돌아가게 한다.</li>
 * </ul>
 * 값을 낮추면 폭주는 줄지만 실패한 발행이 풀리기까지 더 오래 걸린다 — 그 맞바꿈이 조절 손잡이다.
 *
 * <p><b>쿨다운은 공정성 장치지 포기 장치가 아니다.</b> 독약은 쿨다운 주기마다 다시 뽑혀 계속
 * 재시도된다. 다만 그 상태는 <b>인스턴스별 메모리</b>라 — 재기동하면 초기화되고, 여러 인스턴스에서
 * 켜면 각자 따로 센다. 그래서 쿨다운이 인스턴스 사이에서 공유되지 않는다(분산 락은 의존성 추가가
 * 필요해 별도 결정으로 미룬다).
 *
 * <p><b>다중 인스턴스.</b> 분산 락이 없어 <b>여러 인스턴스에서 켜면 같은 건이 N번 재제출된다.</b>
 * 소비자는 전부 멱등이라(C3 까지로 보강) 결과는 안 깨지지만 재제출이 낭비된다 — <b>한 인스턴스에서만
 * 켜는 운영을 전제</b>로 한다({@code worker} 프로파일 등). 여러 대에서 켜야 하면 분산 락(ShedLock 등)이
 * 별도로 필요하다.
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

    /**
     * 같은 발행을 재제출한 뒤 다시 뽑지 않는 동안의 시간. 0 이면 쿨다운 없음(옛 동작).
     *
     * <p><b>필드에 기본값을 둔다</b> — {@code @Value} 는 스프링이 만들어 줄 때만 채워지는데 단위
     * 테스트는 이 서비스를 직접 생성한다. 초기값이 없으면 null 이 되어 터진다.
     */
    @Value("${app.outbox.resubmit.cooldown:PT10M}")
    private Duration cooldown = Duration.ofMinutes(10);

    /**
     * 발행 {@code identifier} → 마지막 재제출 시각. <b>인스턴스 로컬</b>이다(재기동·다중 인스턴스에서
     * 공유되지 않는다 — 클래스 주석 참고). 쿨다운 2배 지난 항목은 틱마다 purge 해 무한히 커지지 않게 한다.
     */
    private final Map<UUID, Instant> lastResubmittedAt = new ConcurrentHashMap<>();

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
        Instant now = Instant.now();
        Instant cutoff = now.minus(minAge);
        Instant cooldownFloor = now.minus(cooldown);
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
            // 쿨다운: 이 발행을 최근에 재제출했으면 이번 틱에는 건너뛴다 — 그만큼 상한 슬롯이 뒤의
            // 건에게 돌아간다(선두 막힘 완화). 슬롯을 소모하지 않으므로 독약이 상한을 다 차지하지 못한다.
            //
            // <b>판정과 기록을 compute 한 번으로 묶는다</b> — get→판정→put 으로 나누면 동시 실행에서 두
            // 스레드가 같은 발행을 함께 통과시킬 수 있다(지금은 fixedDelay 단일 스레드라 드러나지 않지만
            // 그 전제에 기대지 않는다). 슬롯 소모·기록·거절이 한 원자 단위 안에서 일어난다 — 쿨다운 중이면
            // 슬롯도 기록도 건드리지 않고, 상한 초과면 기록하지 않아 다음 틱에 다시 후보가 된다.
            AtomicBoolean accepted = new AtomicBoolean(false);
            lastResubmittedAt.compute(publication.getIdentifier(), (id, last) -> {
                if (last != null && last.isAfter(cooldownFloor)) {
                    return last;   // 쿨다운 중 — 건너뛴다
                }
                if (remaining.getAndDecrement() <= 0) {
                    return last;   // 이번 틱 상한 초과 — 기록하지 않는다
                }
                accepted.set(true);
                resubmitted.incrementAndGet();
                return now;
            });
            return accepted.get();
        });
        purgeExpired(now);
        int count = resubmitted.get();
        if (count > 0) {
            meterRegistry.counter("outbox.resubmitted").increment(count);
            log.info("Outbox 미완료 이벤트 재제출 count={} minAge={} maxPerTick={} cooldown={}",
                    count, minAge, maxPerTick, cooldown);
        }
    }

    /** 쿨다운 2배 지난 항목은 맵에서 지운다 — 틱마다 간단 purge 면 충분하다. */
    private void purgeExpired(Instant now) {
        Instant floor = now.minus(cooldown.multipliedBy(2));
        lastResubmittedAt.values().removeIf(last -> last.isBefore(floor));
    }
}
