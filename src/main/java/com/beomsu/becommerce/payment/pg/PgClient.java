package com.beomsu.becommerce.payment.pg;

/**
 * PG 연동 추상화. 구현체를 갈아끼워 테스트(FakePgClient)·실PG(TossPgClient)·멀티PG(Phase 8)를 지원한다.
 */
public interface PgClient {

    PgApproveResult approve(PgApproveCommand command);

    PgCancelResult cancel(PgCancelCommand command);

    /**
     * 이 결제를 승인한 PG에 물어본다.
     *
     * <p>다른 PG에 물으면 없는 거래라고 답한다. 복구 배치가 그것을 "승인 안 됨"으로 읽고
     * 살아 있는 결제를 실패로 확정한다.
     *
     * <p>단일 PG 어댑터는 자기 자신뿐이므로 {@code provider}를 무시한다.
     */
    default PgQueryResult query(String paymentKey, String provider) {
        return query(paymentKey);
    }

    /** 결제의 실제 상태를 PG에 조회한다. UNKNOWN 복구·대사의 핵심. */
    PgQueryResult query(String paymentKey);
}
