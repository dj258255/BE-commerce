package com.beomsu.becommerce.reconciliation.integrity;

import java.time.Duration;

/**
 * 결제 한 건이 모듈을 건너며 지켜야 하는 불변식(#389).
 *
 * <p>대사({@code ReconciliationService})는 내부 기록과 PG 정산 파일을 맞춘다. 여기는 <b>내부 모듈끼리</b> 맞는지를 본다.
 * 결제는 승인됐는데 주문이 PAID 가 아니거나 원장 분개가 없으면 PG 파일과는 맞아도 우리 쪽 기록이 갈라진 것이다.
 *
 * <p>각 SQL 은 위반한 건의 식별자 한 열({@code ref})을 낸다. {@code ?} 는 기준 시각 하나다. 그보다 최근에 움직인 건은
 * 아직 진행 중일 수 있어 세지 않는다(결제 확정 → 주문 마무리 · 분개는 이벤트로 뒤따른다). 기준 시각은 보통 "지금 − 유예"이고
 * 고정 나이가 있는 불변식은 그 나이를 쓴다.
 */
public enum Invariant {

    /** 결제는 승인됐는데 주문이 PAID 가 아니다. 재고 부족으로 FAILED 가 된 주문은 망취소 불변식이 따로 본다. */
    PAYMENT_DONE_ORDER_NOT_PAID("""
            SELECT p.order_no AS ref FROM payments p JOIN orders o ON o.order_no = p.order_no
            WHERE p.status = 'DONE' AND o.status NOT IN ('PAID', 'FAILED') AND p.approved_at < ?"""),

    /** 주문은 PAID 인데 그 주문의 결제 중 승인된 것이 없다. 전액 포인트 주문은 결제 행이 없어 빠진다. */
    ORDER_PAID_PAYMENT_NOT_DONE("""
            SELECT o.order_no AS ref FROM orders o
            WHERE o.status = 'PAID' AND o.updated_at < ?
              AND EXISTS (SELECT 1 FROM payments p WHERE p.order_no = o.order_no)
              AND NOT EXISTS (SELECT 1 FROM payments p WHERE p.order_no = o.order_no
                              AND p.status IN ('DONE', 'PARTIAL_CANCELED'))"""),

    /** 승인된 결제에 원장 승인 분개가 없다. */
    APPROVED_WITHOUT_LEDGER("""
            SELECT p.order_no AS ref FROM payments p
            WHERE p.status IN ('DONE', 'PARTIAL_CANCELED', 'CANCELED') AND p.approved_at < ?
              AND NOT EXISTS (SELECT 1 FROM ledger_transactions lt WHERE lt.tx_type = 'PAYMENT_APPROVED'
                              AND lt.source_type = 'PAYMENT' AND lt.source_id = p.id)"""),

    /** 승인 분개의 금액이 결제 금액과 다르다. */
    LEDGER_AMOUNT_MISMATCH("""
            SELECT p.order_no AS ref FROM payments p
            JOIN ledger_transactions lt ON lt.tx_type = 'PAYMENT_APPROVED' AND lt.source_type = 'PAYMENT' AND lt.source_id = p.id
            JOIN ledger_entries le ON le.transaction_id = lt.id AND le.direction = 'DEBIT'
            WHERE lt.created_at < ?
            GROUP BY p.order_no, p.amount HAVING SUM(le.amount) <> p.amount"""),

    /** 분개 하나의 차변 합과 대변 합이 다르다. 쓰기 때 막지만(LedgerTransaction) 직접 고친 행은 못 막는다. */
    LEDGER_UNBALANCED("""
            SELECT CAST(lt.id AS CHAR) AS ref FROM ledger_transactions lt JOIN ledger_entries le ON le.transaction_id = lt.id
            WHERE lt.created_at < ?
            GROUP BY lt.id
            HAVING SUM(CASE WHEN le.direction = 'DEBIT' THEN le.amount ELSE 0 END)
                <> SUM(CASE WHEN le.direction = 'CREDIT' THEN le.amount ELSE 0 END)"""),

    /** 승인된 결제에 대사용 내부 기록(승인 행)이 없다. 있어야 PG 정산 파일과 맞출 수 있다. */
    APPROVED_WITHOUT_RECON_RECORD("""
            SELECT p.order_no AS ref FROM payments p
            WHERE p.status IN ('DONE', 'PARTIAL_CANCELED', 'CANCELED') AND p.approved_at < ?
              AND NOT EXISTS (SELECT 1 FROM internal_records ir WHERE ir.order_no = p.order_no AND ir.seq = 0)"""),

    /** 재고 부족으로 FAILED 가 된 주문의 카드가 승인된 채인데 망취소가 걸려 있지 않거나 망취소가 재시도를 다 썼다. */
    FAILED_ORDER_CARD_NOT_REFUNDED("""
            SELECT o.order_no AS ref FROM orders o JOIN payments p ON p.order_no = o.order_no
            WHERE o.status = 'FAILED' AND p.status = 'DONE' AND o.updated_at < ?
              AND NOT EXISTS (SELECT 1 FROM compensation_tasks c WHERE c.order_no = o.order_no
                              AND c.status IN ('PENDING', 'DONE'))"""),

    /** 끝난 주문(PAID · FAILED · EXPIRED · CANCELED)의 재고 예약이 RESERVED 로 남아 재고를 물고 있다. */
    RESERVATION_LEFT_OPEN("""
            SELECT r.order_no AS ref FROM stock_reservations r JOIN orders o ON o.order_no = r.order_no
            WHERE r.status = 'RESERVED' AND o.status IN ('PAID', 'FAILED', 'EXPIRED', 'CANCELED') AND o.updated_at < ?"""),

    /** 재고가 음수다(초과 판매). 유예와 무관하게 센다. */
    STOCK_NEGATIVE("""
            SELECT CAST(s.product_id AS CHAR) AS ref FROM stock s WHERE s.quantity < 0""", Duration.ZERO),

    /** 결과 모름 결제가 10분 넘게 남았다. 복구가 1~2분, 막힌 건도 최대 10분에 확정한다(ADR-057). 유예와 무관한 고정 기준. */
    UNKNOWN_OVER_10_MINUTES("""
            SELECT p.order_no AS ref FROM payments p WHERE p.status = 'UNKNOWN' AND p.requested_at < ?""",
            Duration.ofMinutes(10));

    private final String sql;
    private final Duration fixedAge;

    Invariant(String sql) {
        this(sql, null);
    }

    Invariant(String sql, Duration fixedAge) {
        this.sql = sql;
        this.fixedAge = fixedAge;
    }

    public String sql() {
        return sql;
    }

    /** 유예 대신 쓰는 고정 나이. null 이면 유예를 따른다. */
    Duration fixedAge() {
        return fixedAge;
    }

    boolean takesCutoff() {
        return sql.indexOf('?') >= 0;
    }
}
