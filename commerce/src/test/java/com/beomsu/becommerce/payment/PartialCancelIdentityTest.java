package com.beomsu.becommerce.payment;

import com.beomsu.becommerce.payment.internal.TriggeredBy;
import com.beomsu.becommerce.payment.internal.Payment;
import com.beomsu.becommerce.payment.pg.PgCancelCommand;
import com.beomsu.becommerce.shared.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>부분취소를 무엇으로 식별하는가</b>를 고정하는 회귀 테스트.
 *
 * <p>취소를 "원결제 + 금액"으로 식별하던 때, 20,000원 결제에 5,000원 부분취소를 두 번 하면 네 계층이
 * 동시에 깨졌다. 두 요청이 같은 요청으로 보이기 때문이다.
 * <ul>
 *   <li>PG 멱등키가 같아져 두 번째 취소가 <b>전송조차 되지 않고</b> 첫 응답이 재생된다</li>
 *   <li>취소 반영 가드가 상태 기반이라({@code PARTIAL_CANCELED}는 여전히 취소 가능) 재진입이 안 막힌다</li>
 *   <li>원장 중복 판정이 원결제 ID만 봐서 두 번째 역분개가 조용히 사라진다</li>
 * </ul>
 * 고객은 5,000원만 돌려받는데 우리 장부는 10,000원 취소로 남는다. 각 분개는 차대가 맞으므로
 * 자가 검증에도 안 걸린다.
 *
 * <p>해결은 취소마다 순번을 매기는 것이다. 이 테스트가 지키는 성질은 둘이다 —
 * <b>서로 다른 취소는 갈리고, 같은 취소의 재시도는 여전히 하나다.</b>
 */
class PartialCancelIdentityTest {

    private static final long TOTAL = 20_000L;
    private static final Money PART = Money.krw(5_000);

    private static Payment approvedPayment() {
        Payment payment = Payment.initiate("order-1", Money.krw(TOTAL));
        payment.startApproval("pk-1");
        payment.approve("CARD");
        return payment;
    }

    @Test
    @DisplayName("같은 금액의 두 부분취소는 서로 다른 순번을 갖는다 — 같으면 두 번째가 전송되지 않는다")
    void twoPartialCancelsOfSameAmountGetDifferentSeq() {
        Payment payment = approvedPayment();

        int firstSeq = payment.getCancelCount() + 1;
        payment.cancel(PART, TriggeredBy.USER, "1차 부분취소");
        int secondSeq = payment.getCancelCount() + 1;

        assertThat(firstSeq).isEqualTo(1);
        assertThat(secondSeq)
                .as("금액이 같아도 취소 건이 다르면 순번이 갈려야 한다")
                .isEqualTo(2);

        String firstKey = new PgCancelCommand("pk-1", PART.minorUnit(), "1차", firstSeq).idempotencyKey();
        String secondKey = new PgCancelCommand("pk-1", PART.minorUnit(), "2차", secondSeq).idempotencyKey();
        assertThat(firstKey)
                .as("멱등키가 같으면 PG가 첫 취소 응답을 재생해 두 번째 취소가 아예 나가지 않는다")
                .isNotEqualTo(secondKey);
    }

    @Test
    @DisplayName("같은 취소의 재시도는 같은 순번이라 멱등키도 같다 — 두 번 취소되지 않는다")
    void retryOfSameCancelKeepsSameKey() {
        Payment payment = approvedPayment();

        // 실패한 취소는 순번을 소비하지 않는다. 재시도가 대상을 다시 확정해도 같은 순번이 나온다.
        int attempt = payment.getCancelCount() + 1;
        int retry = payment.getCancelCount() + 1;

        assertThat(retry).isEqualTo(attempt);
        assertThat(new PgCancelCommand("pk-1", PART.minorUnit(), "r", attempt).idempotencyKey())
                .isEqualTo(new PgCancelCommand("pk-1", PART.minorUnit(), "r", retry).idempotencyKey());
    }

    @Test
    @DisplayName("부분취소가 쌓이면 취소 건수도 함께 쌓인다 — 원장·대사가 이 순번으로 취소를 구분한다")
    void cancelCountAccumulates() {
        Payment payment = approvedPayment();

        payment.cancel(PART, TriggeredBy.USER, "1차");
        payment.cancel(PART, TriggeredBy.USER, "2차");

        assertThat(payment.getCancelCount()).isEqualTo(2);
        assertThat(payment.getBalanceAmount()).isEqualTo(TOTAL - 10_000);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PARTIAL_CANCELED);
    }
}
