package com.beomsu.becommerce.order.compensation;

/**
 * 보상 태스크 유형. 이미 낸 승인을 되돌리는 취소를 durable 재시도로 처리한다.
 *
 * <p>이름의 "망취소"는 카드 업계 용어를 그대로 쓴 것인데, 실제로 이 큐에 쌓이는 건 두 종류다.
 * ① 승인 응답을 받지 못해 승인 여부를 모를 때 보내는 진짜 망취소, ② 승인은 났는데 재고가
 * 모자라 되돌리는 당일 승인취소(매입 전 취소). 둘 다 "외부 PG에 취소를 보내야 하고, 실패하면
 * 다시 보내야 한다"는 성질이 같아 한 유형으로 다룬다.
 */
public enum CompensationType {
    /** 이미 낸 승인을 되돌린다 — 외부 PG 호출이라 결과가 불확실해 durable 재시도로 처리한다. */
    NETWORK_CANCEL
}
