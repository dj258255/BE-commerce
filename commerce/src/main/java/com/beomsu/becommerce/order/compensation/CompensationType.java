package com.beomsu.becommerce.order.compensation;

/**
 * 보상 태스크 유형. 이미 낸 승인을 되돌리는 취소를 durable 재시도로 처리한다.
 *
 * <p>이름의 "망취소"는 카드 업계 용어를 그대로 쓴 것이지만, <b>현재 이 큐에 적재되는 경로는 하나뿐</b>이다 —
 * {@code CheckoutTx}(승인은 났는데 재고가 모자란 경우)의 당일 승인취소(매입 전 취소)다.
 * 승인 응답을 받지 못해 승인 여부를 모르는 <b>UNKNOWN 복구는 이 큐가 아니라</b> 복구 배치
 * ({@code PaymentRecoveryService})가 PG 조회로 확정한다 — 그 결제는 아직 취소를 판단할 수 없어
 * 이 큐에 넣어도 취소 대상을 확정할 수 없기 때문이다. 즉 "진짜 망취소도 여기 쌓인다"는 과거 설명은
 * 사실이 아니다.
 */
public enum CompensationType {
    /** 이미 낸 승인을 되돌린다 — 외부 PG 호출이라 결과가 불확실해 durable 재시도로 처리한다. */
    NETWORK_CANCEL
}
