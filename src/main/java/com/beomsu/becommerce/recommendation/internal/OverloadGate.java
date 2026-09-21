package com.beomsu.becommerce.recommendation.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 모델 앞의 문 — <b>넘친 요청을 언제 포기할지</b>를 정한다(E3의 독립변수).
 *
 * <p>여기서 세는 것은 "지금 모델 쪽에 몇 개가 들어가 있는가"뿐이다. 모델 용량 자체는
 * {@code ModelClient} 구현이 갖고 있고, 이 문은 <b>그 앞에서</b> 판단한다 — 그래야 정책을 바꿔도
 * 모델은 그대로다(실험에서 변수를 하나만 움직이려면 이 분리가 필요하다).
 *
 * <h2>정책별 판단</h2>
 *
 * <ul>
 *   <li>{@code UNBOUNDED} — 항상 통과. 줄은 모델 쪽에서 무한히 자란다
 *   <li>{@code BOUNDED} — {@code maxInFlight}를 넘으면 <b>기다리지 않고</b> 거절
 *   <li>{@code ADMISSION} — <b>대기 예상</b>이 예산을 넘으면 거절. 예상은 Little의 법칙으로 낸다:
 *       {@code 대기 ≈ (진행 중 − 모델 동시성) / 처리량}, {@code 처리량 = 동시성 / 지연}.
 *       모델 용량을 알아야 하는 대신 <b>상한을 손으로 고르지 않아도</b> 된다
 * </ul>
 *
 * <p><b>왜 세마포어가 아니라 {@code AtomicInteger}인가</b>: 이 문은 <b>기다리지 않는다</b>(기다리는 것은
 * 모델 쪽이다). 판단과 증가가 한 원자 연산이어야 정책별 상한이 정확히 지켜지므로 CAS 루프를 쓴다.
 */
@Component
public class OverloadGate {

    private static final Logger log = LoggerFactory.getLogger(OverloadGate.class);

    private final OverloadPolicy policy;
    private final int maxInFlight;
    private final long admissionBudgetMs;
    private final int modelConcurrency;
    private final long modelLatencyMs;
    private final AtomicInteger inFlight = new AtomicInteger();

    public OverloadGate(@Value("${app.recommendation.policy:BOUNDED}") OverloadPolicy policy,
                        @Value("${app.recommendation.max-in-flight:24}") int maxInFlight,
                        @Value("${app.recommendation.admission-budget-ms:100}") long admissionBudgetMs,
                        @Value("${app.recommendation.model.concurrency:4}") int modelConcurrency,
                        @Value("${app.recommendation.model.latency-ms:50}") long modelLatencyMs,
                        @Value("${app.recommendation.result-size:12}") int resultSize,
                        @Value("${app.recommendation.generation.scope:RANKING}") GenerationScope scope,
                        @Value("${app.recommendation.generation.ar-prefix:4}") int arPrefix,
                        @Value("${app.recommendation.generation.per-item-ms:15}") long perItemMs) {
        this.policy = policy;
        this.maxInFlight = Math.max(maxInFlight, 1);
        this.admissionBudgetMs = admissionBudgetMs;
        this.modelConcurrency = Math.max(modelConcurrency, 1);
        // 대기 예상은 <b>모델의 실제 지연</b>으로 계산해야 한다. 생성 범위(E5)가 지연을 바꾸므로
        // 스텁과 <b>같은 함수</b>로 같은 값을 낸다 — 다르면 정책이 잘못된 지연으로 판단해
        // 실험이 오염된다(과부하 실험 위에 생성 범위를 얹을 때 조용히 틀리는 자리다).
        this.modelLatencyMs = Math.max(
                scope.estimatedLatencyMs(modelLatencyMs, Math.max(resultSize, 1), arPrefix, perItemMs), 1);
        log.info("과부하 정책={} maxInFlight={} admissionBudget={}ms 모델용량={}동시/{}ms(범위 {})",
                policy, this.maxInFlight, admissionBudgetMs, this.modelConcurrency, this.modelLatencyMs, scope);
    }

    /** 통과시키면 {@code true}. <b>호출자는 반드시 {@link #release()}를 불러야 한다</b>(통과한 경우만). */
    public boolean admit() {
        return switch (policy) {
            case UNBOUNDED -> true;
            case BOUNDED -> tryEnter(maxInFlight);
            case ADMISSION -> {
                int ahead = inFlight.get();
                if (estimatedWaitMs(ahead) > admissionBudgetMs) {
                    yield false;
                }
                // 판단과 증가 사이에 다른 스레드가 끼어들면 상한이 무의미해진다 — 한 연산으로 처리한다.
                yield tryEnter(ahead + 1);
            }
        };
    }

    public void release() {
        inFlight.decrementAndGet();
    }

    public int inFlight() {
        return inFlight.get();
    }

    public OverloadPolicy policy() {
        return policy;
    }

    /**
     * 지금 진행 중인 수가 주는 대기 예상. 모델 동시성을 넘는 몫이 줄을 서고, 그 몫이 한 번에
     * {@code 동시성}개씩 {@code 지연}마다 빠져나간다 → {@code 줄 길이 × 지연 / 동시성}.
     */
    double estimatedWaitMs(int ahead) {
        int queued = Math.max(0, ahead - modelConcurrency + 1);
        return queued * (double) modelLatencyMs / modelConcurrency;
    }

    private boolean tryEnter(int limit) {
        while (true) {
            int current = inFlight.get();
            if (current >= limit) {
                return false;
            }
            if (inFlight.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }
}
