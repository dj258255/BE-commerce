/**
 * 이상거래탐지(fraud/FDS) 모듈.
 *
 * <p>결제를 룰 기반으로 평가해 위험 점수를 매기고 ALLOW/CHALLENGE/BLOCK/REVIEW로 등급을 매긴다.
 * 룰은 코드가 아니라 데이터(임계값·가중치)로 관리해 무배포 조정한다.
 *
 * <p><b>지금은 사후 탐지만 한다. 승인 경로에는 붙어 있지 않다.</b>
 * {@link com.beomsu.becommerce.fraud.FraudService#evaluate} 를 부르는 곳은
 * {@code FraudPostHocListener} 하나이고, 그것은 {@code PaymentConfirmedEvent} 를 받는 경로다.
 * 결제가 이미 끝난 뒤에 다시 평가하는 것이라 <b>{@code BLOCK} 판정이 결제를 막지 않는다</b> —
 * REVIEW/BLOCK 은 심사 큐({@link com.beomsu.becommerce.fraud.FraudReview})에 쌓이고 사람이 사후에 처리한다.
 *
 * <p><b>왜 승인 경로에 안 붙였나</b>: 그 자리에 넣으면 판정 시간이 결제 응답에 그대로 얹힌다.
 * 이 판정은 Redis velocity 카운터를 카드·기기·IP 로 <b>세 번</b> 왕복하므로 공짜가 아니다.
 * 붙일지 말지는 <b>지연 예산을 재고</b> 정한다(docs/17). 재기 전에 붙이는 것은 이 프로젝트가
 * 피해 온 순서다.
 *
 * <p>사후 탐지는 payment 모듈의 {@code PaymentConfirmedEvent}를 구독하고 {@code paymentKey}를
 * 되읽으므로 payment에 의존한다(payment는 fraud를 모르므로 순환 없음).
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = { "shared", "payment", "dispute" }
)
package com.beomsu.becommerce.fraud;

import com.beomsu.becommerce.payment.PaymentConfirmedEvent;
