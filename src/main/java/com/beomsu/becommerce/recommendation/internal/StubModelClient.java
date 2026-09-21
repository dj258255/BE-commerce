package com.beomsu.becommerce.recommendation.internal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

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
 * <p>지연은 {@code Thread.sleep}이다. 실제 모델은 CPU를 태우지만 여기서 재는 것은 <b>대기</b>이므로
 * 재우는 편이 오히려 깨끗하다 — CPU를 태우면 이 장비의 코어 수가 변수가 된다.
 */
@Component
public class StubModelClient implements ModelClient {

    /** 모델의 동시 처리 용량 — 이 수를 넘는 호출은 기다린다. */
    private final Semaphore capacity;
    private final long latencyMs;
    private final long busyTimeoutMs;
    private final int resultSize;

    public StubModelClient(@Value("${app.recommendation.model.concurrency:4}") int concurrency,
                           @Value("${app.recommendation.model.latency-ms:50}") long latencyMs,
                           @Value("${app.recommendation.model.busy-timeout-ms:400}") long busyTimeoutMs,
                           @Value("${app.recommendation.result-size:12}") int resultSize) {
        this.capacity = new Semaphore(Math.max(concurrency, 1), true);
        this.latencyMs = Math.max(latencyMs, 0);
        this.busyTimeoutMs = Math.max(busyTimeoutMs, 1);
        this.resultSize = Math.max(resultSize, 1);
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
            if (latencyMs > 0) {
                Thread.sleep(latencyMs);
            }
            return rank(userId, recentItemIds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelBusyException("모델 추론 중 인터럽트됐다");
        } finally {
            capacity.release();
        }
    }

    /**
     * 결정적 순위 — 같은 입력이면 같은 출력이다(실험이 흔들리지 않아야 한다).
     * 최근 본 것을 앞에 두고, 모자라면 인기 상품으로 채운다(활동이 없는 사용자도 답이 나온다).
     */
    private List<Long> rank(long userId, List<Long> recentItemIds) {
        List<Long> ranked = new ArrayList<>(resultSize);
        for (Long itemId : recentItemIds) {
            if (ranked.size() >= resultSize) {
                break;
            }
            if (!ranked.contains(itemId)) {
                ranked.add(itemId);
            }
        }
        long seed = 1_000_000L + Math.floorMod(userId, 1_000L) * resultSize;
        for (long candidate = seed; ranked.size() < resultSize; candidate++) {
            if (!ranked.contains(candidate)) {
                ranked.add(candidate);
            }
        }
        return List.copyOf(ranked);
    }
}
