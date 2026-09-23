package com.beomsu.becommerce.reconciliation;

import com.beomsu.becommerce.reconciliation.internal.PgSettlementCsvParser;
import com.beomsu.becommerce.reconciliation.internal.ExternalRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PG 정산 파일의 <b>음수 행(환불·챠지백)</b>과 건너뛴 행의 가시성.
 *
 * <p><b>왜 생긴 테스트인가</b>: 파서가 음수를 버리고 있었다. 그런데 {@link ExternalRecord} 계약은
 * "환불·챠지백은 음수로 온다"고 적혀 있고, ADR-013 에서 <b>내부 기록도 취소를 음수 행으로</b>
 * 쌓게 바꿨다. 두 쪽이 어긋나 있었다 — 파서가 음수를 버리면 환불이 있는 날마다
 * 정상 환불이 <b>내부에만 있음</b>으로 잡혀 예외 큐를 채운다.
 *
 * <p>재현으로 확인했다: 승인 10,000 / 환불 -3,000 두 행짜리 파일에서 환불이 사라졌다.
 */
class PgSettlementNegativeAmountTest {

    private final PgSettlementCsvParser parser = new PgSettlementCsvParser();

    private PgSettlementCsvParser.ParseResult parse(String csv) {
        return parser.parse(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("환불 행(음수)을 읽는다 — 버리면 정상 환불이 예외 큐를 채운다")
    void readsNegativeRefundRow() {
        var r = parse("""
                거래일시,orderNo,transaction_id,amount
                2026-08-30 10:00:00,ORD-1,psp-1,10000
                2026-08-30 14:00:00,ORD-1,psp-2,-3000
                """);
        assertThat(r.records()).hasSize(2);
        assertThat(r.records()).extracting(ExternalRecord::amount).containsExactly(10_000L, -3_000L);
        assertThat(r.skipped()).isZero();
    }

    @Test
    @DisplayName("큰 음수도 읽는다")
    void readsLargeNegative() {
        var r = parse("""
                거래일시,orderNo,amount
                2026-08-30 14:00:00,ORD-1,-1234500
                """);
        assertThat(r.records()).extracting(ExternalRecord::amount).containsExactly(-1_234_500L);
    }

    @Test
    @DisplayName("알려진 한계 — 따옴표로 묶인 천 단위 금액은 못 읽는다")
    void knownLimitationQuotedThousandsSeparator() {
        // 파서는 줄을 쉼표로 그냥 자른다(CSV 인용 처리 없음). 그래서 "1,234,500" 은
        // 세 칸으로 쪼개진다. parseAmount 가 쉼표를 지우는 코드를 갖고 있는데,
        // <그 코드에 값이 도달하지 못한다> — 남아 있지만 이 경로에서는 죽은 코드다.
        //
        // 지금 버그로 보고하지 않고 <현재 동작을 고정>한다. 실제 PG 파일이 이 형식으로
        // 오는지 확인되기 전에 CSV 파서를 바꾸면, 확인되지도 않은 문제를 위해
        // 잘 도는 파싱을 흔드는 것이 된다.
        var r = parse("""
                거래일시,orderNo,amount
                2026-08-30 14:00:00,ORD-1,"1,234,500"
                """);
        assertThat(r.records())
                .as("쉼표가 컬럼 구분자로 먹혀 금액이 잘린다 — 이 사실을 알고 있자")
                .extracting(ExternalRecord::amount)
                .isNotEqualTo(java.util.List.of(1_234_500L));
    }

    @Test
    @DisplayName("금액이 있는데 주문번호가 없는 행은 <조사 대상>으로 남긴다")
    void moneyBearingSkipIsVisible() {
        var r = parse("""
                거래일시,orderNo,transaction_id,amount
                2026-08-30 10:00:00,ORD-1,psp-1,10000
                2026-08-30 11:00:00,,psp-x,500000
                합계,,,510000
                """);
        assertThat(r.records()).hasSize(1);
        // 합계행도 함께 잡힌다. <구분할 수 없기 때문에> 그게 맞다 —
        // "합계,,,510000"과 "주문번호가 빠진 51만원 거래"는 파일만 보고 가릴 수 없다.
        // 놓치는 쪽(진짜 돈)보다 사람이 한 번 더 보는 쪽(합계행)이 낫다.
        assertThat(r.moneyBearing())
                .as("금액이 걸린 채 건너뛴 행은 전부 사람이 봐야 한다")
                .hasSize(2)
                .anySatisfy(row -> {
                    assertThat(row.amount()).isEqualTo(500_000L);
                    assertThat(row.reason()).contains("주문번호 없음");
                    assertThat(row.line()).as("원본을 찾아볼 줄 번호").isEqualTo(3);
                });
    }

    @Test
    @DisplayName("금액을 못 읽은 행은 조사 대상이 아니다 — 돈이 걸렸는지 알 수 없다")
    void unparseableAmountIsNotMoneyBearing() {
        var r = parse("""
                거래일시,orderNo,amount
                2026-08-30 10:00:00,ORD-1,합계
                """);
        assertThat(r.skipped()).isEqualTo(1);
        assertThat(r.moneyBearing()).isEmpty();
        assertThat(r.skippedRows()).allSatisfy(x -> assertThat(x.reason()).contains("해석 불가"));
    }
}
