package com.beomsu.pay.fraud.internal;

import com.beomsu.pay.fraud.internal.FraudService;
import com.beomsu.pay.fraud.internal.FraudResult;
import com.beomsu.pay.fraud.internal.FraudPostHocListener;
import com.beomsu.pay.fraud.internal.FraudCheckRequest;
import com.beomsu.pay.fraud.internal.FdsDecision;
import com.beomsu.pay.fraud.review.FraudReviewStatus;
import com.beomsu.pay.fraud.review.FraudReviewRepository;
import com.beomsu.pay.fraud.review.FraudReview;
import com.beomsu.pay.payment.PaymentConfirmedEvent;
import com.beomsu.pay.payment.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class FraudPostHocListenerTest {

    private PaymentService paymentService;
    private FraudService fraudService;
    private FraudReviewRepository reviewRepository;
    private com.beomsu.pay.fraud.model.CardTransactionRepository transactionRepository;
    private com.beomsu.pay.fraud.model.ShadowRiskScorer shadowScorer;
    private FraudPostHocListener listener;

    @BeforeEach
    void setUp() {
        paymentService = mock(PaymentService.class);
        fraudService = mock(FraudService.class);
        reviewRepository = mock(FraudReviewRepository.class);
        transactionRepository = mock(com.beomsu.pay.fraud.model.CardTransactionRepository.class);
        shadowScorer = mock(com.beomsu.pay.fraud.model.ShadowRiskScorer.class);
        when(shadowScorer.score(any(), any(), any())).thenReturn(Optional.empty());
        listener = new FraudPostHocListener(paymentService, fraudService, reviewRepository,
                transactionRepository, shadowScorer);
    }

    private PaymentConfirmedEvent event() {
        return new PaymentConfirmedEvent("ord-1", 10L, 50_000, Instant.parse("2026-07-01T00:00:00Z"));
    }

    @Test
    @DisplayName("REVIEW 판정 → 심사 큐에 적재(orderNo/cardKey/amount 매핑)")
    void reviewFlagsQueue() {
        when(paymentService.historyKeyOf(10L)).thenReturn(Optional.of("card-xyz"));
        when(fraudService.evaluate(any())).thenReturn(
                new FraudResult(70, FdsDecision.REVIEW, List.of("HIGH_AMOUNT")));

        listener.onConfirmed(event());

        ArgumentCaptor<FraudReview> captor = ArgumentCaptor.forClass(FraudReview.class);
        verify(reviewRepository).save(captor.capture());
        FraudReview saved = captor.getValue();
        assertThat(saved.getOrderNo()).isEqualTo("ord-1");
        assertThat(saved.getPaymentId()).isEqualTo(10L);
        assertThat(saved.getCardKey()).isEqualTo("card-xyz");
        assertThat(saved.getAmount()).isEqualTo(50_000);
        assertThat(saved.getDecision()).isEqualTo(FdsDecision.REVIEW);
        assertThat(saved.getStatus()).isEqualTo(FraudReviewStatus.PENDING);
        assertThat(saved.getReasons()).isEqualTo("HIGH_AMOUNT");
    }

    @Test
    @DisplayName("BLOCK 판정 → 긴급 심사 대상으로 적재")
    void blockFlagsQueue() {
        when(paymentService.historyKeyOf(10L)).thenReturn(Optional.of("card-xyz"));
        when(fraudService.evaluate(any())).thenReturn(
                new FraudResult(120, FdsDecision.BLOCK, List.of("BLACKLISTED_CARD")));

        listener.onConfirmed(event());

        verify(reviewRepository).save(any(FraudReview.class));
    }

    @Test
    @DisplayName("ALLOW 판정 → 큐에 적재하지 않음")
    void allowDoesNotFlag() {
        when(paymentService.historyKeyOf(10L)).thenReturn(Optional.of("card-xyz"));
        when(fraudService.evaluate(any())).thenReturn(
                new FraudResult(0, FdsDecision.ALLOW, List.of()));

        listener.onConfirmed(event());

        verify(reviewRepository, never()).save(any());
    }

    @Test
    @DisplayName("CHALLENGE 판정 → 큐에 적재하지 않음")
    void challengeDoesNotFlag() {
        when(paymentService.historyKeyOf(10L)).thenReturn(Optional.of("card-xyz"));
        when(fraudService.evaluate(any())).thenReturn(
                new FraudResult(40, FdsDecision.CHALLENGE, List.of("VELOCITY_EXCEEDED(6)")));

        listener.onConfirmed(event());

        verify(reviewRepository, never()).save(any());
    }

    @Test
    @DisplayName("paymentKey 조회 empty → 아무 것도 하지 않음(평가/적재 skip)")
    void missingPaymentKeySkips() {
        when(paymentService.historyKeyOf(10L)).thenReturn(Optional.empty());

        listener.onConfirmed(event());

        verify(fraudService, never()).evaluate(any());
        verify(reviewRepository, never()).save(any());
    }

    @Test
    @DisplayName("사후 재평가는 cardKey+amount로 평가한다(ip/device/userId는 0/null)")
    void evaluatesWithCardKeyAndAmount() {
        when(paymentService.historyKeyOf(10L)).thenReturn(Optional.of("card-xyz"));
        when(fraudService.evaluate(any())).thenReturn(
                new FraudResult(0, FdsDecision.ALLOW, List.of()));

        listener.onConfirmed(event());

        ArgumentCaptor<FraudCheckRequest> captor = ArgumentCaptor.forClass(FraudCheckRequest.class);
        verify(fraudService).evaluate(captor.capture());
        FraudCheckRequest req = captor.getValue();
        assertThat(req.cardKey()).isEqualTo("card-xyz");
        assertThat(req.amount()).isEqualTo(50_000);
        assertThat(req.userId()).isEqualTo(0L);
        assertThat(req.ip()).isNull();
        assertThat(req.deviceId()).isNull();
    }

    @Test
    @DisplayName("판정보다 먼저 거래 이력을 남긴다 — 이번 건이 창에 들어야 escalation 이 맞는다")
    void recordsHistoryBeforeEvaluating() {
        when(paymentService.historyKeyOf(10L)).thenReturn(Optional.of("card-xyz"));
        when(fraudService.evaluate(any())).thenReturn(
                new FraudResult(0, FdsDecision.ALLOW, List.of()));
        when(transactionRepository.existsByOrderNo("ord-1")).thenReturn(false);

        listener.onConfirmed(event());

        var order = org.mockito.Mockito.inOrder(transactionRepository, fraudService);
        order.verify(transactionRepository).save(any());
        order.verify(fraudService).evaluate(any());
    }

    @Test
    @DisplayName("같은 이벤트가 두 번 와도 이력은 한 줄이다 — 아웃박스는 at-least-once 다")
    void redeliveryDoesNotDuplicateHistory() {
        when(paymentService.historyKeyOf(10L)).thenReturn(Optional.of("card-xyz"));
        when(fraudService.evaluate(any())).thenReturn(
                new FraudResult(0, FdsDecision.ALLOW, List.of()));
        when(transactionRepository.existsByOrderNo("ord-1")).thenReturn(true);

        listener.onConfirmed(event());

        org.mockito.Mockito.verify(transactionRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    @DisplayName("이력 저장이 터져도 판정은 돈다 — 관찰이 판정을 막으면 안 된다")
    void historyFailureDoesNotStopDetection() {
        when(paymentService.historyKeyOf(10L)).thenReturn(Optional.of("card-xyz"));
        when(transactionRepository.existsByOrderNo("ord-1"))
                .thenThrow(new IllegalStateException("DB 죽음"));
        when(fraudService.evaluate(any())).thenReturn(
                new FraudResult(70, FdsDecision.REVIEW, List.of("HIGH_AMOUNT")));

        listener.onConfirmed(event());

        org.mockito.Mockito.verify(reviewRepository).save(any());
    }

    @Test
    @DisplayName("섀도는 심사 큐를 안 건드린다 — ALLOW 면 큐에 아무것도 안 들어간다")
    void shadowDoesNotTouchTheQueue() {
        when(paymentService.historyKeyOf(10L)).thenReturn(Optional.of("card-xyz"));
        when(fraudService.evaluate(any())).thenReturn(
                new FraudResult(0, FdsDecision.ALLOW, List.of()));
        when(shadowScorer.score(any(), any(), any())).thenReturn(Optional.of(0.99));

        listener.onConfirmed(event());

        org.mockito.Mockito.verify(shadowScorer).score(any(), any(), any());
        org.mockito.Mockito.verify(reviewRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    @DisplayName("섀도 점수를 심사에 실어 보낸다 — 큐 정렬이 그 값을 쓴다")
    void shadowScoreIsCarriedIntoTheReview() {
        when(paymentService.historyKeyOf(10L)).thenReturn(Optional.of("card-xyz"));
        when(fraudService.evaluate(any())).thenReturn(
                new FraudResult(70, FdsDecision.REVIEW, List.of("HIGH_AMOUNT")));
        when(shadowScorer.score(any(), any(), any())).thenReturn(Optional.of(0.87));

        listener.onConfirmed(event());

        var saved = org.mockito.ArgumentCaptor.forClass(
                com.beomsu.pay.fraud.review.FraudReview.class);
        org.mockito.Mockito.verify(reviewRepository).save(saved.capture());
        assertThat(saved.getValue().getModelRisk()).isEqualTo(0.87);
    }

    @Test
    @DisplayName("홀드아웃이라 점수가 없어도 큐에는 들어간다 — 집합은 규칙이 정한다")
    void heldOutReviewStillEntersTheQueue() {
        when(paymentService.historyKeyOf(10L)).thenReturn(Optional.of("card-xyz"));
        when(fraudService.evaluate(any())).thenReturn(
                new FraudResult(70, FdsDecision.REVIEW, List.of("HIGH_AMOUNT")));
        when(shadowScorer.score(any(), any(), any())).thenReturn(Optional.empty());

        listener.onConfirmed(event());

        var saved = org.mockito.ArgumentCaptor.forClass(
                com.beomsu.pay.fraud.review.FraudReview.class);
        org.mockito.Mockito.verify(reviewRepository).save(saved.capture());
        assertThat(saved.getValue().getModelRisk()).isNull();
    }

    @Test
    @DisplayName("모델이 높게 봐도 규칙이 ALLOW 면 큐에 안 넣는다 — 집합을 모델이 못 바꾼다")
    void modelCannotAddToTheQueue() {
        when(paymentService.historyKeyOf(10L)).thenReturn(Optional.of("card-xyz"));
        when(fraudService.evaluate(any())).thenReturn(
                new FraudResult(0, FdsDecision.ALLOW, List.of()));
        when(shadowScorer.score(any(), any(), any())).thenReturn(Optional.of(0.99));

        listener.onConfirmed(event());

        org.mockito.Mockito.verify(reviewRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    @DisplayName("같은 이벤트가 두 번 와도 심사 큐는 한 줄이다 — 재발행 멱등")
    void redeliveryDoesNotDuplicateReview() {
        when(paymentService.historyKeyOf(10L)).thenReturn(Optional.of("card-xyz"));
        when(fraudService.evaluate(any())).thenReturn(
                new FraudResult(70, FdsDecision.REVIEW, List.of("HIGH_AMOUNT")));
        when(reviewRepository.existsByOrderNo("ord-1")).thenReturn(true);

        listener.onConfirmed(event());

        org.mockito.Mockito.verify(reviewRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    @DisplayName("동시 진입을 유니크 제약이 막아도 예외를 삼킨다 — 재배달은 정상이다")
    void duplicateReviewIsSwallowed() {
        when(paymentService.historyKeyOf(10L)).thenReturn(Optional.of("card-xyz"));
        when(fraudService.evaluate(any())).thenReturn(
                new FraudResult(70, FdsDecision.REVIEW, List.of("HIGH_AMOUNT")));
        when(reviewRepository.existsByOrderNo("ord-1")).thenReturn(false);
        when(reviewRepository.save(any(FraudReview.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("dup"));

        listener.onConfirmed(event());   // 예외가 밖으로 새지 않아야 한다
    }
}
