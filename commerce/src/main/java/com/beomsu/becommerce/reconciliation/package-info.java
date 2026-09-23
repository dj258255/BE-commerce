/**
 * 대사(reconciliation) 모듈.
 *
 * <p>PG 정산 파일과 내부 기록을 transaction_key로 매칭해 4분류(일치/내부만/외부만/금액불일치).
 * 결정적(deterministic) 매칭 엔진 + 예외 큐. "결제 시스템의 최종 방어선".
 * <p>수기 확정 사유는 {@code audit} 모듈에 append-only로 남긴다(ADR-008). 그래서 audit에 의존한다 —
 * audit은 reconciliation을 모르므로 순환은 없다.
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = { "shared", "payment", "audit" }
)
package com.beomsu.becommerce.reconciliation;
