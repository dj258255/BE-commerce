package com.beomsu.becommerce.personalization;

import org.springframework.modulith.events.Externalized;

import java.time.Instant;

/**
 * 사용자 활동 이벤트 — 근사선 컨텍스트 갱신의 입력.
 *
 * <p><b>합성이다.</b> 실제 사용자 행동이 아니라 부하 생성기가 흘린 것이고, {@code source}에
 * {@code SYNTHETIC}을 실어 실데이터와 구분한다({@code personalization/docs/00-data.md} §5:
 * 공개 데이터에 없는 impression·session은 만들어 쓰되 명확히 표시한다).
 *
 * <p><b>Zero-Payload 지향</b>: 컨텍스트 갱신에 필요한 최소만 담는다. 소비자가 상세를 필요로 하면
 * 페이로드를 신뢰 원천으로 삼지 말고 되읽는다 — 순서 역전·스키마 결합을 피하는 이 저장소의 규칙이다
 * ({@code PaymentConfirmedEvent}와 같은 이유).
 *
 * <p><b>{@code seq}</b>는 생성기가 부여하는 사용자별 단조 증가 순번이다. 컨텍스트 적용이 이 값으로
 * 순서를 판정하므로(작거나 같으면 버린다) <b>at-least-once 재배달과 순서 역전이 같은 규칙 하나로
 * 막힌다.</b> 읽기 쪽의 {@code expectSeq}도 같은 값을 쓴다 — "내가 낸 이벤트가 반영됐는가"를
 * 물으려면 그 번호를 알아야 한다.
 *
 * <p><b>{@code @Externalized}</b>: 라우팅 키를 {@code userId}로 잡아 <b>한 사용자의 활동이 같은
 * 파티션</b>에 들어가 순서가 보존되게 한다. 이 성질 덕분에 컨슈머가 여러 스레드여도 사용자별
 * read-modify-write에 분산 락이 필요 없다(같은 사용자의 이벤트는 같은 스레드가 처리한다).
 *
 * <p>이 모듈은 이벤트를 <b>항상 발행</b>한다. 전달 방식이 {@code IN_PROCESS}·{@code IN_REQUEST}여도
 * 발행은 유지된다 — 지금 소비자가 없더라도 프로세스 밖 소비자가 생길 때 경로가 이미 열려 있게 하려는
 * 것이고, 그 "옵션의 값"은 이 실험이 재지 못한 항목이다(리포트의 "남은 것").
 */
@Externalized("user.activity::#{userId}")
public record UserActivityEvent(long userId, long itemId, String activityType, long seq,
                                Instant occurredAt, String source) {
}
