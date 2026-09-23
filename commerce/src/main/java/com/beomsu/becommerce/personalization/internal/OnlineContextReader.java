package com.beomsu.becommerce.personalization.internal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 온라인 컨텍스트 읽기 + <b>대기 정책</b>.
 *
 * <p>E1의 비교군이 여기 있다: 즉시 응답(0ms) / 50 / 100 / 200 / 최신까지 대기. 컨텍스트가 아직
 * {@code expectSeq}에 도달하지 않았으면 그만큼 기다렸다가 다시 읽는다.
 *
 * <p><b>무한 대기는 열지 않는다.</b> {@code max-wait-ms}로 상한을 두고, 상한을 넘으면
 * {@code reflected=false}로 <b>정직하게</b> 돌려준다 — 기다린 시간({@code waitedMs})을 함께 주므로
 * "얼마를 기다렸는데 못 받았는가"가 남는다. 이 두 값이 곡선의 좌표가 된다.
 *
 * <p>폴링 간격이 0이면 바쁜 대기가 되므로 최소값을 둔다(기본 10ms). 폴링 방식의 대가는 명확하다:
 * 기다리는 동안 저장소를 반복해서 때린다. 그래서 E1의 p95에는 이 반복 조회 비용이 포함되고,
 * 그 사실을 리포트에 적는다.
 */
@Service
public class OnlineContextReader {

    private final ContextStore store;
    private final long pollIntervalMs;
    private final long maxWaitMs;

    public OnlineContextReader(ContextStore store,
                               @Value("${app.personalization.context.poll-interval-ms:10}") long pollIntervalMs,
                               @Value("${app.personalization.context.max-wait-ms:1000}") long maxWaitMs) {
        this.store = store;
        this.pollIntervalMs = Math.max(1, pollIntervalMs);
        this.maxWaitMs = Math.max(0, maxWaitMs);
    }

    public ContextView read(long userId, Long expectSeq, Long waitMs) {
        long budget = Math.min(Math.max(waitMs == null ? 0L : waitMs, 0L), maxWaitMs);
        long start = System.nanoTime();

        Optional<OnlineContext> context = store.read(userId);
        while (needsWait(context, expectSeq) && elapsedMs(start) < budget) {
            sleep();
            context = store.read(userId);
        }

        long waitedMs = elapsedMs(start);
        if (context.isEmpty()) {
            return ContextView.empty(userId, waitedMs);
        }
        OnlineContext found = context.get();
        boolean reflected = expectSeq == null || found.reached(expectSeq);
        return ContextView.of(userId, found, reflected, waitedMs, ContextView.SOURCE_CONTEXT);
    }

    private boolean needsWait(Optional<OnlineContext> context, Long expectSeq) {
        return expectSeq != null && (context.isEmpty() || !context.get().reached(expectSeq));
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private void sleep() {
        try {
            Thread.sleep(pollIntervalMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
