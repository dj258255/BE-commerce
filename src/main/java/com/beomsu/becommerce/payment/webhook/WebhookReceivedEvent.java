package com.beomsu.becommerce.payment.webhook;

/**
 * 웹훅 수신 완료 이벤트 — "빠른 200 응답 후 비동기 해석"의 트리거.
 *
 * <p>{@link WebhookService#receive}가 서명 검증·멱등 저장을 마친 <b>신규</b> 이벤트에 대해서만
 * 발행한다. Modulith Event Publication Registry(Outbox)에 수신 트랜잭션과 함께 기록되어,
 * 커밋 이후 {@code @ApplicationModuleListener}가 <b>별도 스레드에서</b> PG 조회 해석을 수행한다.
 * 덕분에 컨트롤러는 PG 네트워크 콜을 기다리지 않고 즉시 200을 돌려준다(PG 10초 규약 대응).
 *
 * <p>아웃박스에 실려 있어 앱이 해석 전에 죽어도 재기동 시 재발행된다 — 단순 {@code @Async}가
 * 크래시 시 태스크를 잃는 것과 다르다.
 *
 * @param webhookEventId 저장된 {@link WebhookEvent}의 PK — 리스너가 이 id로 다시 로드해 해석한다.
 */
public record WebhookReceivedEvent(Long webhookEventId) {
}
