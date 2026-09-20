package com.beomsu.becommerce.payment.pg;

import com.beomsu.becommerce.payment.pg.TossPgClient.TossPayment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 토스 응답 → 도메인 타입 매핑 검증 (HTTP 없이).
 *
 * <p>여기서 지키는 계약은 하나다: <b>모르는 것을 실패로 단정하지 않는다.</b>
 */
class TossPgClientMappingTest {

    private static TossPayment done(long amount) {
        return new TossPayment("DONE", "카드", amount, amount, null, null);
    }

    @Test
    @DisplayName("승인 응답 DONE + 금액 일치 → SUCCESS")
    void confirmDone() {
        PgApproveResult r = TossPgClient.mapConfirm(done(10_000), 10_000);
        assertThat(r.outcome()).isEqualTo(PgOutcome.SUCCESS);
        assertThat(r.method()).isEqualTo("카드");
    }

    @Test
    @DisplayName("승인 금액이 요청과 다르면 성공으로 처리하지 않고 예외 — 위변조·연동 오류 방어")
    void confirmAmountMismatch() {
        assertThatThrownBy(() -> TossPgClient.mapConfirm(done(9_000), 10_000))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("승인 금액 불일치");
    }

    @Test
    @DisplayName("승인 응답이 DONE이 아니면 FAILED, 응답이 비었으면 미확정(TIMEOUT)")
    void confirmNotDone() {
        assertThat(TossPgClient.mapConfirm(new TossPayment("ABORTED", null, null, null, null, null), 10_000)
                .outcome()).isEqualTo(PgOutcome.FAILED);
        // 응답 자체가 없으면 승인 여부를 모른다 → 실패로 단정하지 않는다
        assertThat(TossPgClient.mapConfirm(null, 10_000).outcome()).isEqualTo(PgOutcome.TIMEOUT);
    }

    @Test
    @DisplayName("조회 상태 매핑: 진행 중은 '결제 없음'으로 뭉개지 않는다")
    void queryStatusMapping() {
        assertThat(TossPgClient.mapStatus("DONE")).isEqualTo(PgPaymentStatus.APPROVED);
        assertThat(TossPgClient.mapStatus("CANCELED")).isEqualTo(PgPaymentStatus.CANCELED);
        assertThat(TossPgClient.mapStatus("PARTIAL_CANCELED")).isEqualTo(PgPaymentStatus.CANCELED);
        // 승인 실패가 확정된 상태만 NOT_FOUND
        assertThat(TossPgClient.mapStatus("ABORTED")).isEqualTo(PgPaymentStatus.NOT_FOUND);
        assertThat(TossPgClient.mapStatus("EXPIRED")).isEqualTo(PgPaymentStatus.NOT_FOUND);
        assertThat(TossPgClient.mapStatus(null)).isEqualTo(PgPaymentStatus.NOT_FOUND);
        // 진행 중을 NOT_FOUND로 두면 복구 배치가 살아 있는 결제를 실패로 확정해 버린다
        assertThat(TossPgClient.mapStatus("IN_PROGRESS")).isEqualTo(PgPaymentStatus.IN_PROGRESS);
        assertThat(TossPgClient.mapStatus("READY")).isEqualTo(PgPaymentStatus.IN_PROGRESS);
        assertThat(TossPgClient.mapStatus("WAITING_FOR_DEPOSIT")).isEqualTo(PgPaymentStatus.IN_PROGRESS);
        // 모르는 상태도 확정하지 않는다
        assertThat(TossPgClient.mapStatus("SOMETHING_NEW")).isEqualTo(PgPaymentStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("승인 응답의 카드 정보로 지문을 만든다 — 이게 있어야 같은 카드가 묶인다")
    void confirmCarriesCardFingerprint() {
        var card = new TossPayment.Card("12345678****123*", "3K");
        var resp = new TossPayment("DONE", "카드", 10_000L, 10_000L, null, card);

        String key = TossPgClient.mapConfirm(resp, 10_000).cardFingerprint();

        assertThat(key).isEqualTo(CardFingerprint.of("12345678****123*", "3K"));
        assertThat(key).as("마스킹 값이라도 그대로 실어 나르면 안 된다").doesNotContain("12345678");
    }

    @Test
    @DisplayName("카드 결제가 아니면 지문이 없다 — 없는 것을 빈 값으로 묶으면 남남이 한 카드가 된다")
    void nonCardPaymentHasNoFingerprint() {
        var resp = new TossPayment("DONE", "계좌이체", 10_000L, 10_000L, null, null);

        assertThat(TossPgClient.mapConfirm(resp, 10_000).cardFingerprint()).isNull();
    }

    @Test
    @DisplayName("취소 응답에서 PG가 발급한 transactionKey를 꺼낸다")
    void cancelTransactionKey() {
        TossPayment resp = new TossPayment("CANCELED", "카드", 10_000L, 0L,
                List.of(new TossPayment.Cancel("txn-1", 5_000L),
                        new TossPayment.Cancel("txn-2", 5_000L)), null);
        assertThat(TossPgClient.transactionKeyOf(resp, "pk")).isEqualTo("txn-2");
        assertThat(TossPgClient.transactionKeyOf(null, "pk")).isEqualTo("toss-pk");
    }

    // --- 에러 코드 분류: HTTP 상태 코드가 아니라 토스 코드로 판정한다 ---

    @Test
    @DisplayName("카드사 거절은 확정 실패 — 재시도해도 같은 답이 온다")
    void declinedCodes() {
        assertThat(TossErrorCodes.classify("REJECT_CARD_COMPANY"))
                .isEqualTo(TossErrorCodes.Kind.DECLINED);
        assertThat(TossErrorCodes.classify("INVALID_STOPPED_CARD"))
                .isEqualTo(TossErrorCodes.Kind.DECLINED);
        assertThat(TossErrorCodes.classify("REJECT_CARD_PAYMENT"))
                .isEqualTo(TossErrorCodes.Kind.DECLINED);
    }

    @Test
    @DisplayName("PROVIDER_ERROR는 HTTP 400이지만 일시 오류다 — 실패로 단정하면 안 된다")
    void providerErrorIsRetryable() {
        assertThat(TossErrorCodes.classify("PROVIDER_ERROR"))
                .isEqualTo(TossErrorCodes.Kind.RETRYABLE);
        assertThat(TossErrorCodes.classify("FAILED_PAYMENT_INTERNAL_SYSTEM_PROCESSING"))
                .isEqualTo(TossErrorCodes.Kind.RETRYABLE);
    }

    @Test
    @DisplayName("은행 점검 시간은 확정 실패다 — 미확정으로 두면 사용자가 기다린 뒤 실패를 통보받는다")
    void bankNotAvailableIsDeclinedNotUnknown() {
        // 은행이 서비스 시간이 아니면 승인이 나가지 않았음이 확실하다. 미확정 큐에 넣을 이유가 없고,
        // 넣으면 202를 받은 사용자가 복구 주기만큼 기다린 뒤에야 실패를 안다.
        assertThat(TossErrorCodes.classify("NOT_AVAILABLE_BANK"))
                .isEqualTo(TossErrorCodes.Kind.DECLINED);
    }

    @Test
    @DisplayName("ALREADY_PROCESSED_PAYMENT는 실패가 아니라 이미 승인된 것 — 조회로 확정해야 한다")
    void alreadyProcessedIsNotFailure() {
        assertThat(TossErrorCodes.classify("ALREADY_PROCESSED_PAYMENT"))
                .isEqualTo(TossErrorCodes.Kind.ALREADY_APPROVED);
    }

    @Test
    @DisplayName("이미 취소된 결제는 취소 요청 입장에선 성공(멱등)")
    void alreadyCanceledIsIdempotentSuccess() {
        assertThat(TossErrorCodes.classify("ALREADY_CANCELED_PAYMENT"))
                .isEqualTo(TossErrorCodes.Kind.ALREADY_CANCELED);
        assertThat(TossErrorCodes.classify("ALREADY_REFUND_PAYMENT"))
                .isEqualTo(TossErrorCodes.Kind.ALREADY_CANCELED);
    }

    @Test
    @DisplayName("취소 불가·기간 경과는 사람이 봐야 한다")
    void notCancelable() {
        assertThat(TossErrorCodes.classify("NOT_CANCELABLE_PAYMENT"))
                .isEqualTo(TossErrorCodes.Kind.NOT_CANCELABLE);
        assertThat(TossErrorCodes.classify("EXCEED_MAX_REFUND_DUE"))
                .isEqualTo(TossErrorCodes.Kind.NOT_CANCELABLE);
    }

    @Test
    @DisplayName("우리 키·요청이 틀린 것은 승인이 안 나갔음이 확실하다")
    void requestError() {
        assertThat(TossErrorCodes.classify("UNAUTHORIZED_KEY"))
                .isEqualTo(TossErrorCodes.Kind.REQUEST_ERROR);
        assertThat(TossErrorCodes.classify("DUPLICATED_ORDER_ID"))
                .isEqualTo(TossErrorCodes.Kind.REQUEST_ERROR);
    }

    @Test
    @DisplayName("모르는 코드는 미확정으로 본다 — 결제에서 모르는 것을 실패로 단정하면 더 비싸다")
    void unknownCodeIsConservative() {
        assertThat(TossErrorCodes.classify("SOME_NEW_CODE_2027"))
                .isEqualTo(TossErrorCodes.Kind.RETRYABLE);
        assertThat(TossErrorCodes.classify(null)).isEqualTo(TossErrorCodes.Kind.RETRYABLE);
        assertThat(TossErrorCodes.classify("")).isEqualTo(TossErrorCodes.Kind.RETRYABLE);
    }
}
