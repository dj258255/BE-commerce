package com.beomsu.becommerce.live;

/**
 * media(live)가 주문의 결제 결과를 묻기 위한 포트(R13, R32·ADR-085) — {@link ProductLookup}·
 * {@link OrderPlacement}과 같은 모양(인터페이스는 media에, 구현은 commerce 쪽 분리 패키지에).
 * Redis 선점(TTL)이 5분을 넘긴 홀드를 돌려줄지 유지할지는 이 결과로만 정한다 — media는 결제·
 * 주문 상태머신을 전혀 모르고, commerce가 이미 아는 사실(지금 이 주문이 어떤 상태인가)을
 * 그대로 받아 쓴다.
 */
public interface OrderPaymentStatus {

    /** 이 주문번호의 지금 결제 결과. 주문이 없으면 {@link Outcome#OTHER}(더 잡아 둘 이유가 없다). */
    Outcome outcomeOf(String orderNo);

    enum Outcome {
        /** 결제 완료 — 이 선점은 영구히 확정된다(R13). */
        PAID,
        /** 승인 진행 중(결과 모름, UNKNOWN) — 기존 결제 복구 흐름이 확정할 때까지 선점을 유지한다(R13.2). */
        IN_PROGRESS,
        /** 그 외(미결제 포기·실패·취소·만료·주문 없음) — 선점을 돌려준다(R13.1). */
        OTHER
    }
}
