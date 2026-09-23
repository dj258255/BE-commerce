/**
 * 개인화(personalization) 모듈 — 온라인 컨텍스트의 근사선 갱신과 온라인 읽기.
 *
 * <p><b>이 모듈이 하는 일</b>: 사용자 활동(합성)을 받아 컨텍스트 저장소(Redis)에 반영하고,
 * 요청 시 그 컨텍스트를 읽어 <b>얼마나 최신인지</b>를 함께 돌려준다. 추천 모델·홈 구성은 여기 없다
 * (M5·M7). 여기서 재는 것은 <b>신선도와 지연의 교환비</b>다
 * ({@code personalization/docs/02-experiments.md} E1).
 *
 * <p><b>평면</b>({@code docs/01-architecture.md} §2): 이 모듈은 근사선(nearline)과 온라인(online)을
 * 함께 담는다. 근사선은 "이벤트 소비 → 컨텍스트 갱신", 온라인은 "요청 시 컨텍스트 조회"다.
 *
 * <p><b>전달 방식이 셋인 이유</b>: 브로커(Kafka)가 이 기능에 필요한지 재기 위한 것이다.
 * 적용 로직({@code ContextApplier})은 하나를 공유하고 <b>누가 언제 적용하는지</b>만
 * {@code app.personalization.transport}로 바꾼다 — 그래야 전달 방식 외의 변수가 섞이지 않는다.
 * <ul>
 *   <li>{@code IN_PROCESS} — 커밋 후 {@code @ApplicationModuleListener}. <b>기본값</b>이고
 *       브로커가 필요 없다. 순서 보장이 없어도 되는 이유는 병합이 순서에 무관하기 때문이다(ADR-035)</li>
 *   <li>{@code KAFKA} — Outbox → 브로커 → 인앱 컨슈머. 프로세스 밖 소비자가 필요할 때 켠다.
 *       {@code kafka} 프로파일이 있어야 하고, 없으면 컨텍스트가 갱신되지 않는다</li>
 *   <li>{@code IN_REQUEST} — 같은 트랜잭션에서 갱신. 순서는 지켜지지만 동시 요청은 겹치고,
 *       저장소 장애가 활동 기록을 롤백시킨다</li>
 * </ul>
 *
 * <p><b>경계</b>({@code docs/01-architecture.md} §5): 커머스 도메인 테이블을 직접 조회하지 않는다.
 * 그래서 활동의 {@code itemId}는 {@code products}를 논리적으로만 가리키고 검증하지 않는다.
 * 의존은 {@code shared}(에러 코드 체계)뿐이고, 어떤 모듈도 이 모듈을 의존하지 않는다 —
 * 그래서 통째로 떼어낼 수 있는 형태가 유지된다.
 *
 * <p><b>주의</b>: {@code kafka} 프로파일이 아니면 {@code KAFKA} 전달은 아무것도 받지 못한다
 * (브로커가 없으면 발행도 소비도 없다). 그 사실은 기동 로그와 문서에 남긴다.
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = { "shared" }
)
package com.beomsu.becommerce.personalization;
