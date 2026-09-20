package com.beomsu.becommerce.payment.pg;

import java.util.Set;

/**
 * 토스페이먼츠 에러 코드를 우리 3-상태(승인·거절·미확정)로 옮기는 분류표.
 *
 * <p><b>HTTP 상태 코드만 보면 안 되는 이유가 여기 있다.</b> 400인데 "잠시 후 다시 시도해주세요"인 코드가
 * 있고({@code PROVIDER_ERROR}), 400인데 이미 승인이 끝난 코드가 있다({@code ALREADY_PROCESSED_PAYMENT}).
 * 4xx를 일괄 실패로 단정하면 고객 돈은 나갔는데 우리 기록만 실패가 되는 최악의 불일치가 생긴다.
 *
 * <p>모르는 코드는 {@link Kind#RETRYABLE}로 본다. 결제에서 모르는 것을 실패로 단정하는 쪽이
 * 미확정으로 남겨 복구 배치에 넘기는 쪽보다 훨씬 비싸기 때문이다.
 *
 * <p>출처: 토스페이먼츠 개발자센터 API 에러 코드 문서.
 */
final class TossErrorCodes {

    /** 카드사·계좌가 명시적으로 거절했다. 다시 보내도 같은 답이 온다. */
    private static final Set<String> DECLINED = Set.of(
            "INVALID_REJECT_CARD",              // 카드 사용 거절, 카드사 문의 필요
            "INVALID_STOPPED_CARD",             // 정지된 카드
            "INVALID_CARD_LOST_OR_STOLEN",      // 분실·도난 카드
            "REJECT_CARD_PAYMENT",              // 한도 초과·잔액 부족
            "REJECT_CARD_COMPANY",              // 카드사 승인 거절
            "REJECT_ACCOUNT_PAYMENT",           // 계좌 잔액 부족
            "INVALID_CARD_EXPIRATION",
            "INVALID_CARD_NUMBER",
            "EXCEED_MAX_CARD_INSTALLMENT_PLAN",
            // 은행 점검 시간이면 승인이 나가지 않았음이 확실하다. 미확정 큐에 넣으면 사용자는
            // 202를 받고 기다린 뒤 실패를 통보받는다. 즉시 실패로 알리고 다른 수단을 권하는 게 맞다.
            "NOT_AVAILABLE_BANK");

    /** 일시적 오류. 승인이 됐는지 안 됐는지 모른다 → 실패로 단정하지 않고 미확정으로 남긴다. */
    private static final Set<String> RETRYABLE = Set.of(
            "PROVIDER_ERROR",                              // 400인데 "잠시 후 다시 시도"
            "FAILED_PAYMENT_INTERNAL_SYSTEM_PROCESSING",   // 500
            "UNKNOWN_PAYMENT_ERROR",                       // 500
            "FAILED_INTERNAL_SYSTEM_PROCESSING",
            "FAILED_REFUND_PROCESS");                      // 취소: 은행 지연·일시 오류

    /** 이미 승인된 결제. 실패가 아니므로 조회로 실제 상태를 확정해야 한다. */
    private static final Set<String> ALREADY_APPROVED = Set.of(
            "ALREADY_PROCESSED_PAYMENT");

    /** 이미 취소된 결제. 취소 요청 입장에선 원하던 상태이므로 성공으로 본다(멱등). */
    private static final Set<String> ALREADY_CANCELED = Set.of(
            "ALREADY_CANCELED_PAYMENT",
            "ALREADY_REFUND_PAYMENT");

    /** 취소가 불가능한 상태. 재시도해도 소용없고 사람이 봐야 한다. */
    private static final Set<String> NOT_CANCELABLE = Set.of(
            "NOT_CANCELABLE_PAYMENT",
            "NOT_CANCELABLE_AMOUNT",
            "EXCEED_MAX_REFUND_DUE");                      // 환불 가능 기간 경과

    /** 우리 쪽 키·요청이 틀렸다. 승인이 나가지 않았음이 확실하다. */
    private static final Set<String> REQUEST_ERROR = Set.of(
            "UNAUTHORIZED_KEY",
            "INCORRECT_BASIC_AUTH_FORMAT",
            "NOT_FOUND_PAYMENT",
            "NOT_FOUND_PAYMENT_SESSION",
            "DUPLICATED_ORDER_ID",
            "INVALID_REQUEST");

    private TossErrorCodes() {
    }

    static Kind classify(String code) {
        if (code == null || code.isBlank()) {
            return Kind.RETRYABLE;                 // 코드조차 못 읽었으면 모르는 것이다
        }
        if (DECLINED.contains(code)) {
            return Kind.DECLINED;
        }
        if (ALREADY_APPROVED.contains(code)) {
            return Kind.ALREADY_APPROVED;
        }
        if (ALREADY_CANCELED.contains(code)) {
            return Kind.ALREADY_CANCELED;
        }
        if (NOT_CANCELABLE.contains(code)) {
            return Kind.NOT_CANCELABLE;
        }
        if (REQUEST_ERROR.contains(code)) {
            return Kind.REQUEST_ERROR;
        }
        return Kind.RETRYABLE;                     // 미등록 코드는 보수적으로 미확정
    }

    enum Kind {
        /** 확정 실패 — 결제를 FAILED로 기록해도 안전하다. */
        DECLINED,
        /** 미확정 — 승인 여부를 모른다. 복구 배치가 조회로 확정한다. */
        RETRYABLE,
        /** 이미 승인됨 — 조회로 확정한다. */
        ALREADY_APPROVED,
        /** 이미 취소됨 — 취소 요청은 성공으로 본다. */
        ALREADY_CANCELED,
        /** 취소 불가 — 사람이 봐야 한다. */
        NOT_CANCELABLE,
        /** 우리 요청이 틀림 — 승인 안 나감이 확실. */
        REQUEST_ERROR
    }
}
