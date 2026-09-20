/**
 * 분쟁/차지백(dispute) 모듈 — 카드 결제의 필수 사후 처리.
 *
 * <p>카드 결제는 승인으로 끝나지 않는다. 고객이 카드사에 이의를 제기하면(chargeback) 가맹점은
 * 정해진 기한 안에 증빙을 제출해 다퉈야 하고, 그 승패에 따라 정산·원장을 되돌려야 한다. 이 모듈은
 * 그 흐름을 상태머신으로 담는다: 차지백 수신 → 분쟁 개시(OPEN) → 증빙 제출(EVIDENCE_SUBMITTED)
 * → 승/패(WON/LOST). 상태 전이는 엔티티({@link com.beomsu.becommerce.dispute.Dispute})가 가드하며
 * 허용되지 않은 전이는 예외로 막는다.
 *
 * <p>차지백 식별자({@code chargebackId})를 멱등키로 삼아 PG의 중복 웹훅을 안전하게 흡수한다.
 * 패소(LOST) 시 {@link com.beomsu.becommerce.dispute.DisputeLostEvent}를 발행해 ledger 모듈이 원매출을
 * 역분개(reversal)하도록 한다 — dispute는 ledger를 모르고, ledger가 이 이벤트를 구독한다(순환 없음).
 *
 * <p>차지백 웹훅은 payment 모듈의 웹훅 서명 검증기(HMAC)를 그대로 재사용한다
 * ({@code payment :: webhook} 명명 인터페이스).
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = { "shared", "payment", "payment :: webhook" }
)
package com.beomsu.becommerce.dispute;
