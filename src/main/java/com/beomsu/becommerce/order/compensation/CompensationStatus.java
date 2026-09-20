package com.beomsu.becommerce.order.compensation;

/** 보상 태스크 처리 상태. */
public enum CompensationStatus {
    /** 처리 대기 — 스케줄러가 nextAttemptAt 도래 시 재시도한다. */
    PENDING,
    /** 처리 완료(멱등 완료 포함). */
    DONE,
    /** 재시도 소진 — 자동 처리를 포기하고 운영이 개입해야 한다. */
    FAILED
}
