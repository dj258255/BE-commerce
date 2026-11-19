package com.beomsu.becommerce.order.compensation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class CompensationTaskTest {

    private CompensationTask task() {
        return CompensationTask.networkCancel("ord-1", 14_000, "재고 부족: 카드 승인 후 자동 망취소");
    }

    @Test
    @DisplayName("생성 직후: PENDING, retryCount=0, maxRetries=5, 즉시 시도 가능")
    void newTaskIsPending() {
        CompensationTask t = task();
        assertThat(t.getStatus()).isEqualTo(CompensationStatus.PENDING);
        assertThat(t.getRetryCount()).isEqualTo(0);
        assertThat(t.getMaxRetries()).isEqualTo(5);
        assertThat(t.getType()).isEqualTo(CompensationType.NETWORK_CANCEL);
        assertThat(t.isExhausted()).isFalse();
    }

    @Test
    @DisplayName("recordFailure: maxRetries 미만이면 PENDING 유지 + nextAttemptAt 갱신")
    void recordFailureBelowMaxStaysPending() {
        CompensationTask t = task();
        Instant next = Instant.now().plusSeconds(60);

        t.recordFailure("일시 오류", next);

        assertThat(t.getRetryCount()).isEqualTo(1);
        assertThat(t.getStatus()).isEqualTo(CompensationStatus.PENDING);
        assertThat(t.getNextAttemptAt()).isEqualTo(next);
        assertThat(t.getLastError()).isEqualTo("일시 오류");
    }

    @Test
    @DisplayName("recordFailure 경계: retryCount가 maxRetries에 도달하면 FAILED")
    void recordFailureAtMaxBecomesFailed() {
        CompensationTask t = task();
        for (int i = 0; i < 4; i++) {
            t.recordFailure("실패", Instant.now().plusSeconds(60));
            assertThat(t.getStatus()).isEqualTo(CompensationStatus.PENDING); // 1~4회는 PENDING
        }
        t.recordFailure("최종 실패", Instant.now().plusSeconds(60)); // 5회째

        assertThat(t.getRetryCount()).isEqualTo(5);
        assertThat(t.getStatus()).isEqualTo(CompensationStatus.FAILED);
        assertThat(t.isExhausted()).isTrue();
    }

    @Test
    @DisplayName("lastError 최대 길이 방어: 500자 초과분은 잘라 저장한다")
    void recordFailureTruncatesLongError() {
        CompensationTask t = task();
        String longError = "x".repeat(1000);

        t.recordFailure(longError, Instant.now().plusSeconds(60));

        assertThat(t.getLastError()).hasSize(500);
    }

    @Test
    @DisplayName("markDone: DONE으로 확정")
    void markDoneConfirms() {
        CompensationTask t = task();
        t.markDone();
        assertThat(t.getStatus()).isEqualTo(CompensationStatus.DONE);
    }

    @Test
    @DisplayName("reopen: 소진(FAILED) 태스크를 재무장 — PENDING + retryCount 0 + nextAttemptAt 갱신")
    void reopenRearmsFailedTask() {
        CompensationTask t = task();
        Instant far = Instant.now().plusSeconds(3600);
        for (int i = 0; i < 5; i++) {
            t.recordFailure("실패", far);
        }
        assertThat(t.getStatus()).isEqualTo(CompensationStatus.FAILED);
        assertThat(t.getNextAttemptAt()).isEqualTo(far); // 소진 시엔 nextAttemptAt이 미래로 남는다

        t.reopen();

        assertThat(t.getStatus()).isEqualTo(CompensationStatus.PENDING);
        assertThat(t.getRetryCount()).isEqualTo(0);
        assertThat(t.isExhausted()).isFalse();
        // 즉시 시도 가능하게 nextAttemptAt이 now로 당겨진다(미래 예약이 사라짐).
        assertThat(t.getNextAttemptAt()).isBefore(far);
        assertThat(t.getNextAttemptAt()).isBeforeOrEqualTo(Instant.now());
    }

    @Test
    @DisplayName("시간 창 규칙(#476): 횟수가 maxRetries 를 넘어도 포기 시각 전이면 PENDING")
    void windowRuleIgnoresCountBeforeGiveUp() {
        CompensationTask t = task();
        Instant giveUpAt = Instant.now().plusSeconds(1800);
        for (int i = 0; i < 20; i++) {
            t.recordFailure("PG 취소 장애", Instant.now().plusSeconds(60), CompensationOutcome.FAILED_DEFINITE, giveUpAt);
        }
        assertThat(t.getRetryCount()).isEqualTo(20);
        assertThat(t.getStatus()).isEqualTo(CompensationStatus.PENDING);
        assertThat(t.isExhausted()).isFalse();
    }

    @Test
    @DisplayName("시간 창 규칙(#476): 포기 시각이 지나면 한 번의 실패로도 FAILED")
    void windowRuleFailsAfterGiveUp() {
        CompensationTask t = task();
        t.recordFailure("PG 취소 장애", Instant.now().plusSeconds(60), CompensationOutcome.FAILED_DEFINITE,
                Instant.now().minusSeconds(1));
        assertThat(t.getRetryCount()).isEqualTo(1);
        assertThat(t.isExhausted()).isTrue();
    }

    @Test
    @DisplayName("시간 창 규칙(#476): 포기 시각이 null 이면 지금 규칙(5회째 FAILED)과 같다")
    void nullGiveUpKeepsCountRule() {
        CompensationTask t = task();
        for (int i = 0; i < 4; i++) {
            t.recordFailure("실패", Instant.now().plusSeconds(60), null, null);
        }
        assertThat(t.isExhausted()).isFalse();
        t.recordFailure("실패", Instant.now().plusSeconds(60), null, null);
        assertThat(t.isExhausted()).isTrue();
    }
}
