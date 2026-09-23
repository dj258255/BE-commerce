package com.beomsu.becommerce.recommendation.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.LongPredicate;

/**
 * 실험용 모델 스텁 — <b>용량과 지연을 설정으로 고정한 모델 서버</b>.
 *
 * <p><b>왜 스텁인가</b>: E3가 재는 것은 모델의 품질이 아니라 <b>모델이 느릴 때 시스템이 무엇을
 * 포기하는가</b>다. 그래서 모델은 "동시 {@code concurrency}개, 각 {@code latency}ms"라는
 * 성질 하나로 충분하다 — 처리량 = 동시성/지연으로 고정된다.
 *
 * <p><b>한계를 숨기지 않는다</b>: 이것은 <b>같은 프로세스 안의 세마포어</b>다. 실제 모델 서버와 달리
 * 네트워크 왕복·직렬화·커넥션 풀이 없다. 그래서 여기서 나온 절대 수치(ms)를 "실제 모델의 지연"으로
 * 읽으면 안 되고, <b>정책 간 비교</b>로만 읽어야 한다. 지연은 어차피 설정값이므로 이 실험에서
 * 네트워크가 사라지는 것은 변수를 하나 줄이는 일이지 결론을 흐리는 일이 아니다.
 *
 * <p><b>학습 모델로 바꿔 보고 안 바꿨다</b>: ALS를 실제로 학습해 같은 하네스로 쟀더니 MAP@12가
 * 0.007608로, 넘어야 할 기준선 {@code repeat_last} 0.023354의 3분의 1이었다. 인기보다도 낮다.
 * alpha·factors 두 축을 열어도 15% 안에서만 움직였다. 그래서 스텁을 <b>남겨 둔 것</b>이지
 * 안 해 본 것이 아니다 — 근거는 ADR-048이다.
 *
 * <p>지연은 {@code Thread.sleep}이다. 실제 모델은 CPU를 태우지만 여기서 재는 것은 <b>대기</b>이므로
 * 재우는 편이 오히려 깨끗하다 — CPU를 태우면 이 장비의 코어 수가 변수가 된다.
 */
@Component
public class StubModelClient implements ModelClient {

    private static final Logger log = LoggerFactory.getLogger(StubModelClient.class);

    /** 모델의 동시 처리 용량 — 이 수를 넘는 호출은 기다린다. */
    private final Semaphore capacity;
    /** 실제로 재우는 시간 — 범위(E5)에 따라 달라진다. 생성 구간이 직렬로 붙는다. */
    private final long effectiveLatencyMs;
    private final long busyTimeoutMs;
    private final int resultSize;
    /** 무엇을 추천할 수 있는가 — 카탈로그(기본) 또는 실험용 합성 풀. */
    private final ItemPoolSource pool;

    public StubModelClient(@Value("${app.recommendation.model.concurrency:4}") int concurrency,
                           @Value("${app.recommendation.model.latency-ms:50}") long latencyMs,
                           @Value("${app.recommendation.model.busy-timeout-ms:400}") long busyTimeoutMs,
                           @Value("${app.recommendation.result-size:12}") int resultSize,
                           @Value("${app.recommendation.generation.scope:RANKING}") GenerationScope scope,
                           @Value("${app.recommendation.generation.ar-prefix:4}") int arPrefix,
                           @Value("${app.recommendation.generation.per-item-ms:15}") long perItemMs,
                           ItemPoolSource pool) {
        this.pool = pool;
        this.capacity = new Semaphore(Math.max(concurrency, 1), true);
        this.resultSize = Math.max(resultSize, 1);
        // 생성 범위가 직렬 구간을 정한다 — 랭킹은 항목 수와 무관, AR 은 항목 수에 비례(E5).
        // 스텁이므로 CPU 가 아니라 <b>대기 시간</b>으로 모델링한다: 재는 것이 처리량(동시성/지연)이라
        // 코어 수가 변수로 끼면 안 된다.
        this.effectiveLatencyMs = scope.estimatedLatencyMs(latencyMs, this.resultSize, arPrefix, perItemMs);
        this.busyTimeoutMs = Math.max(busyTimeoutMs, 1);
        log.info("모델 스텁={} 직렬 {}항목 → 지연 {}ms (기준 {}ms, 항목당 {}ms) 용량 {}동시",
                scope, scope.sequentialItems(this.resultSize, arPrefix), effectiveLatencyMs,
                Math.max(latencyMs, 0), Math.max(perItemMs, 0), Math.max(concurrency, 1));
    }

    @Override
    public List<Long> recommend(long userId, List<Long> recentItemIds) {
        boolean acquired;
        try {
            acquired = capacity.tryAcquire(busyTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelBusyException("모델 대기 중 인터럽트됐다");
        }
        if (!acquired) {
            // 기다리다 지친 것과 죽은 것은 다르다 — 이 예외가 그 구분이다.
            throw new ModelBusyException("모델 용량 대기 초과: " + busyTimeoutMs + "ms");
        }
        try {
            if (effectiveLatencyMs > 0) {
                Thread.sleep(effectiveLatencyMs);
            }
            return rank(userId, recentItemIds, ALLOW_ALL);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelBusyException("모델 추론 중 인터럽트됐다");
        } finally {
            capacity.release();
        }
    }

    /** 제약이 없을 때. 상수로 두어 기존 경로에 분기가 안 생기게 한다. */
    private static final LongPredicate ALLOW_ALL = id -> true;

    /**
     * 결정적 순위 — 같은 입력이면 같은 출력이다(실험이 흔들리지 않아야 한다).
     * 최근 본 것을 앞에 두고, 모자라면 인기 상품으로 채운다(활동이 없는 사용자도 답이 나온다).
     *
     * <p>{@code allowed}가 거부한 후보는 <b>건너뛰고 다음으로 채운다</b>. 그래서 목록이 짧아지지
     * 않는다 — 사후 필터와 갈리는 지점이 여기다. 대가는 <b>건너뛴 만큼 더 묻는 것</b>이다.
     */
    private List<Long> rank(long userId, List<Long> recentItemIds, LongPredicate allowed) {
        List<Long> ranked = new ArrayList<>(resultSize);
        for (Long itemId : recentItemIds) {
            if (ranked.size() >= resultSize) {
                break;
            }
            if (!ranked.contains(itemId) && allowed.test(itemId)) {
                ranked.add(itemId);
            }
        }
        // 채우는 id를 후보 집합에서 가져오는 이유: E4 의 제약 확인이 **실제로 걸러낼 대상**이 응답에
        // 들어 있어야 위반율이 의미를 갖는다. 풀이 갈라지면 위반율이 0으로 나오고, 그것은
        // "확인이 잘 해서"가 아니라 "확인할 게 없어서"다.
        // 시작점을 사용자마다 돌려 "모두 같은 목록" 편향을 줄인다(순서만 돌리므로 결정적이다).
        List<Long> popular = pool.popular();
        if (popular.isEmpty()) {
            // 후보가 없으면 빈손으로 답한다 — 폴백도 카탈로그가 비면 그릴 것이 없다.
            return List.copyOf(ranked);
        }
        int offset = (int) Math.floorMod(userId, popular.size());
        for (int i = 0; ranked.size() < resultSize && i < popular.size(); i++) {
            Long candidate = popular.get((offset + i) % popular.size());
            if (!ranked.contains(candidate) && allowed.test(candidate)) {
                ranked.add(candidate);
            }
        }
        return List.copyOf(ranked);
    }

    /**
     * 이 스텁은 생성 중 제약을 <b>받을 수 있다</b>. 같은 프로세스 안에 있으니까다.
     *
     * <p><b>실제 모델 서버는 대개 못 한다</b> — 우리 재고를 알 이유가 없다. 그 차이가 이 실험이
     * 재려는 결합도 비용이고, 그래서 이 값을 계약에 드러내 두었다.
     */
    @Override
    public boolean supportsConstrainedGeneration() {
        return true;
    }

    @Override
    public List<Long> recommend(long userId, List<Long> recentItemIds, LongPredicate allowed) {
        boolean acquired;
        try {
            acquired = capacity.tryAcquire(busyTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelBusyException("모델 대기 중 인터럽트됐다");
        }
        if (!acquired) {
            throw new ModelBusyException("모델 용량 대기 초과: " + busyTimeoutMs + "ms");
        }
        try {
            if (effectiveLatencyMs > 0) {
                Thread.sleep(effectiveLatencyMs);
            }
            return rank(userId, recentItemIds, allowed);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelBusyException("모델 추론 중 인터럽트됐다");
        } finally {
            capacity.release();
        }
    }
}
