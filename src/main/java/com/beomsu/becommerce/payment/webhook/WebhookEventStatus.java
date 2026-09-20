package com.beomsu.becommerce.payment.webhook;

/** 웹훅 이벤트 처리 상태. */
public enum WebhookEventStatus {

    /** 수신·저장 완료. 아직 해석(조회 재검증) 전. */
    RECEIVED,

    /** 조회 API로 실상태를 재검증해 처리 완료. */
    PROCESSED,

    /** 처리 대상이 아니라 건너뜀(예: paymentKey 없음, 중복 수신). */
    SKIPPED,

    /** 처리 중 오류 — 다음 주기(폴링/대사)에서 재처리 대상. */
    /**
     * 웹훅이 승인 응답보다 <b>먼저</b> 도착해 아직 결제 행이 없다. 실패가 아니라 대기다.
     * {@code WebhookPendingScheduler} 가 재시도하고, 상한을 넘기면 FAILED 로 넘어간다.
     */
    PENDING_PAYMENT,

    FAILED
}
