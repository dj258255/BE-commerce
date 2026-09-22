package com.beomsu.becommerce.order.web;

import com.beomsu.becommerce.shared.DomainException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.UUID;

/**
 * 도메인 예외 → HTTP 응답 변환.
 *
 * <p>{@link DomainException}의 {@code code}를 10-API-스펙 문서의 에러 코드 체계에 맞는 HTTP 상태로
 * 매핑하고, {@code {code, message, traceId}} JSON으로 응답한다. traceId는 요청 추적용으로 생성한다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ErrorResponse> handleDomain(DomainException ex) {
        HttpStatus status = statusOf(ex.code());
        ErrorResponse body = new ErrorResponse(ex.code(), ex.getMessage(), UUID.randomUUID().toString());
        return ResponseEntity.status(status).body(body);
    }

    /**
     * 유니크 제약 위반 — 도메인 예외로 흡수되지 않은 <b>무결성 위반의 최후 방어선</b>이다.
     *
     * <p>지금 실질적으로 걸리는 곳은 위시리스트의 동시 추가다. 사전 조회를 두 요청이 동시에
     * 통과하면 두 번째 INSERT가 {@code uk_wishlist_user_product}에 막힌다. 이때 500을 내보내면
     * 하트를 두 번 누른 사용자에게 서버 오류가 보인다 — 실제로는 <b>의도한 결과(1건)</b>가
     * 이뤄졌으므로 409로 알리고 클라이언트가 다시 조회하게 한다.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleIntegrity(DataIntegrityViolationException ex) {
        ErrorResponse body = new ErrorResponse("DUPLICATE_REQUEST",
                "같은 요청이 동시에 들어왔습니다. 잠시 후 다시 확인해 주세요.", UUID.randomUUID().toString());
        return ResponseEntity.status(statusOf("DUPLICATE_REQUEST")).body(body);
    }

    private HttpStatus statusOf(String code) {
        return switch (code) {
            case "AMOUNT_MISMATCH", "ORDER_FORBIDDEN", "MAKER_CHECKER_VIOLATION",
                 "SUBSCRIPTION_FORBIDDEN"
                    -> HttpStatus.FORBIDDEN;                                                 // 403
            case "ORDER_NOT_FOUND", "PAYMENT_NOT_FOUND", "PRODUCT_NOT_FOUND",
                 "FORCE_CANCEL_NOT_FOUND", "FRAUD_REVIEW_NOT_FOUND",
                 "SETTLEMENT_NOT_FOUND", "SUBSCRIPTION_NOT_FOUND",
                 "MEMBER_NOT_FOUND", "DISPUTE_NOT_FOUND", "REVIEW_NOT_FOUND" -> HttpStatus.NOT_FOUND;             // 404
            case "ORDER_ALREADY_PAID", "PAYMENT_RESULT_PENDING",
                 "INVALID_STATE_TRANSITION", "CANCEL_AMOUNT_EXCEEDED", "OUT_OF_STOCK",
                 "INVALID_FRAUD_REVIEW_STATE", "SUBSCRIPTION_NOT_ACTIVE",
                 "INVALID_SUBSCRIPTION_TRANSITION", "INVALID_DISPUTE_TRANSITION",
                 "INSUFFICIENT_BALANCE", "LIMIT_EXCEEDED", "WALLET_CONCURRENCY",
                 "IDEMPOTENT_REQUEST_PROCESSING", "EMAIL_ALREADY_EXISTS",
                 "PAYOUT_RECONCILIATION_REQUIRED",
                 // 순서 위반은 오류가 아니라 설계된 거절이다(ADR-014 블라인드 리뷰)
                 "REVIEW_OUT_OF_ORDER", "PAYMENT_ALREADY_SETTLED",
                 // 동시 요청이 유니크 제약에 부딪힌 경우(위시리스트 동시 추가). 결과는 이미 의도한 대로다.
                 "DUPLICATE_REQUEST" -> HttpStatus.CONFLICT; // 409
            case "IDEMPOTENCY_KEY_REUSED" -> HttpStatus.UNPROCESSABLE_ENTITY;                // 422
            // 대기열 게이트: 요청 자체는 유효하나 지금은 받아줄 수 없다(줄 서면 됨) → 403(권한 문제)이
            // 아니라 429가 의미에 맞다. 클라이언트는 enter → status 폴링 후 재시도하면 된다.
            case "QUEUE_PASS_REQUIRED", "AUTH_HASH_CAPACITY" -> HttpStatus.TOO_MANY_REQUESTS; // 429
            default -> HttpStatus.BAD_REQUEST;                                               // 400 (INVALID_* 포함)
        };
    }

    public record ErrorResponse(String code, String message, String traceId) {
    }
}
