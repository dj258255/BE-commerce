package com.beomsu.becommerce.order.idempotency;

import com.beomsu.becommerce.shared.DomainException;

/** 멱등키 관련 예외. 코드는 토스페이먼츠/10-API-스펙의 멱등 시맨틱과 일치한다. */
public class IdempotencyException extends DomainException {

    public IdempotencyException(String code, String message) {
        super(code, message);
    }

    /** 같은 키의 이전 요청이 아직 처리 중 — 클라이언트는 잠시 후 같은 키로 재시도. (409) */
    public static IdempotencyException processing(String key) {
        return new IdempotencyException("IDEMPOTENT_REQUEST_PROCESSING",
                "이전 요청이 처리 중입니다. 잠시 후 다시 시도해 주세요: " + key);
    }

    /** 같은 멱등키인데 요청 본문이 다름 — 위험한 재사용. (422) */
    public static IdempotencyException reused(String key) {
        return new IdempotencyException("IDEMPOTENCY_KEY_REUSED",
                "같은 멱등키로 다른 내용을 요청했습니다: " + key);
    }

    /** 멱등키 형식 오류(누락/과다). (400) */
    public static IdempotencyException invalidKey(String reason) {
        return new IdempotencyException("INVALID_IDEMPOTENCY_KEY", reason);
    }
}
