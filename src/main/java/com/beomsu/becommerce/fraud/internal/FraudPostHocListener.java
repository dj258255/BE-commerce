package com.beomsu.becommerce.fraud.internal;

import com.beomsu.becommerce.order.internal.CheckoutService;
import com.beomsu.becommerce.fraud.model.CardTransaction;
import com.beomsu.becommerce.fraud.model.CardTransactionRepository;
import com.beomsu.becommerce.fraud.model.ShadowRiskScorer;
import com.beomsu.becommerce.fraud.model.TxnRecord;
import com.beomsu.becommerce.fraud.review.FraudReviewRepository;
import com.beomsu.becommerce.fraud.review.FraudReview;
import com.beomsu.becommerce.payment.PaymentConfirmedEvent;
import com.beomsu.becommerce.payment.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * 비동기 사후 탐지 리스너 — 결제 완료 이벤트를 받아 판정 엔진으로 다시 평가하고, REVIEW/BLOCK이면
 * 심사 큐에 적재한다.
 *
 * <p>결제 크리티컬 경로({@code CheckoutService.confirm}/{@code PaymentService.confirm})는 절대
 * 건드리지 않는다. 사후 탐지는 오직 {@code PaymentConfirmedEvent} 구독으로만 붙어, 승인이 끝난 뒤
 * (Outbox at-least-once) 비동기로 돈다 — 결제 지연·실패에 영향을 주지 않는다. 동기 인라인 판정과
 * <b>같은 판정 엔진</b>({@link FraudService#evaluate})을 재사용해 룰 일관성을 지킨다.
 *
 * <p><b>사후 신호의 한계</b>: 요청 시점의 신호(ip/deviceId/userId)는 이벤트에 없다(Zero-Payload).
 * 그래서 사후 탐지는 cardKey({@code paymentKey})와 amount만으로 평가한다 — 활성 룰(블랙리스트·
 * velocity·금액 이상치)이 모두 cardKey·amount 기준이라 정상 동작하며, ip/device는 0/null로 채운다.
 *
 * <p>BLOCK은 사후엔 이미 결제가 완료돼 막을 수 없지만, 긴급 심사 대상으로 큐에 적재한다.
 * ALLOW/CHALLENGE는 큐에 넣지 않는다.
 *
 * <p><b>여기서 셋을 한다.</b> 순서가 뜻을 갖는다.
 * <ol>
 *   <li><b>거래 이력을 남긴다</b> — 모델이 볼 창이다. 판정보다 먼저 해야 이번 건이 창에 든다</li>
 *   <li><b>규칙으로 판정한다</b> — 지금까지 하던 일. 이것만 심사 큐를 채운다</li>
 *   <li><b>모델로 섀도 채점한다</b> — 점수를 기록만 하고 <b>아무것도 안 막는다</b></li>
 * </ol>
 * 1과 3은 실패해도 2를 멈추지 않는다. 관찰이 판정을 막으면 안 된다.
 */
@Component
@RequiredArgsConstructor
// 하위 패키지가 같은 모듈 안에서 참조하므로 public 이다. 모듈 밖 접근은 package-private 이 아니라
// ModularityTests 의 allowedDependencies 가 막는다.
public class FraudPostHocListener {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(FraudPostHocListener.class);

    private final PaymentService paymentService;
    private final FraudService fraudService;
    private final FraudReviewRepository reviewRepository;
    private final CardTransactionRepository transactionRepository;
    private final ShadowRiskScorer shadowScorer;

    @ApplicationModuleListener
    void onConfirmed(PaymentConfirmedEvent e) {
        // 이벤트는 카드 키를 싣지 않으므로 paymentId로 되읽는다. 없으면 조용히 skip.
        // <b>카드 지문이 있으면 그것으로 묶는다.</b> 없으면 paymentKey 로 떨어지는데, 그때는
        // 그 건이 자기 자신하고만 묶여 창에 한 줄만 들어온다 — 과거를 못 본 점수가 된다.
        String cardKey = paymentService.historyKeyOf(e.paymentId()).orElse(null);
        if (cardKey == null) {
            return;
        }

        // 이번 건이 창에 들어야 escalation·windowCount 가 이번 결제를 반영한다. 판정보다 먼저 한다.
        var current = new TxnRecord(e.amount(), java.time.Instant.now(), null, null, 0);
        record(cardKey, e.orderNo(), current);

        // 사후 재평가: ip/deviceId/userId는 요청 시점 신호라 사후엔 없다 → 0/null.
        // 활성 룰이 cardKey·amount 기준이라 이 입력으로도 정상 동작한다.
        FraudResult result = fraudService.evaluate(
                new FraudCheckRequest(0L, cardKey, null, null, e.amount()));

        // 섀도. 점수를 먼저 낸다. 심사에 실어 보내야 큐가 그 순서로 정렬된다.
        // <b>큐에 넣고 빼는 데는 안 쓴다.</b> 아래 조건은 규칙 판정만 본다(docs/27 5-1절).
        Double risk = shadowScorer.score(cardKey, e.orderNo(), current).orElse(null);

        // REVIEW/BLOCK만 심사 큐에 적재한다(ALLOW/CHALLENGE는 제외).
        // 집합은 규칙이 정하고 순서만 모델이 정한다. 모델을 꺼도 이 조건은 그대로다.
        if (result.decision() == FdsDecision.REVIEW || result.decision() == FdsDecision.BLOCK) {
            flagOnce(e, cardKey, result, risk);
        }
    }

    /**
     * 심사 큐 적재 — <b>멱등.</b>
     *
     * <p>아웃박스는 at-least-once 라 재발행으로 같은 이벤트가 두 번 온다(재기동 재발행·프로듀서
     * 재시도). 그때 사후 재평가가 REVIEW/BLOCK 을 다시 내려도 <b>한 주문에 심사는 하나</b>여야 한다.
     * 이력은 {@link #record} 에서 순서 유니크로 막고 있었는데, 사람이 보는 큐는 막는 장치가 없었다.
     *
     * <p>검사만으로는 동시 진입을 못 막는다(상황 2.2). 그래서 먼저 보고, 그 사이에 끼어들면
     * DB 유니크 제약({@code uk_fraud_review_order})이 마지막에 막는다.
     */
    private void flagOnce(PaymentConfirmedEvent e, String cardKey, FraudResult result, Double risk) {
        try {
            if (reviewRepository.existsByOrderNo(e.orderNo())) {
                return;
            }
            reviewRepository.save(FraudReview.flagged(
                    e.orderNo(), e.paymentId(), cardKey, e.amount(), result, risk));
        } catch (org.springframework.dao.DataIntegrityViolationException dup) {
            // 유니크 제약이 막은 것이다. 재배달이라 정상이다.
            log.debug("[fds] 이미 심사 큐에 있는 주문이라 적재를 건너뜁니다 order={}", e.orderNo());
        }
    }

    /**
     * 거래 이력 한 줄. <b>실패해도 판정을 멈추지 않는다.</b>
     *
     * <p>아웃박스가 at-least-once 라 같은 이벤트가 두 번 온다. 저장 전에 먼저 보고, 그 사이에
     * 끼어들면 유니크 제약이 마지막에 막는다. <b>검사만으로는 못 막는다</b>는 것을 상황 2.2 에서
     * 인스턴스 둘로 실제로 뚫어 봤다.
     */
    private void record(String cardKey, String orderNo, TxnRecord current) {
        try {
            if (transactionRepository.existsByOrderNo(orderNo)) {
                return;
            }
            transactionRepository.save(CardTransaction.of(
                    cardKey, orderNo, current.amount(), current.installmentMonths(),
                    current.deviceId(), current.ip(), current.at()));
        } catch (org.springframework.dao.DataIntegrityViolationException dup) {
            // 유니크 제약이 막은 것이다. 재배달이라 정상이다.
            log.debug("[fds] 이미 담은 주문이라 거래 이력을 건너뜁니다 order={}", orderNo);
        } catch (RuntimeException ex) {
            log.warn("[fds] 거래 이력 저장 실패 order={}", orderNo, ex);
        }
    }
}
