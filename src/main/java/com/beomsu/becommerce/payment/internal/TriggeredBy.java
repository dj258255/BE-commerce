package com.beomsu.becommerce.payment.internal;

/** 상태 전이를 일으킨 주체. 감사(audit)와 대사에서 "누가 이 전이를 만들었나"를 추적한다. */
public enum TriggeredBy {
    USER,
    WEBHOOK,
    POLLING,
    RECOVERY_BATCH,
    ADMIN
}
