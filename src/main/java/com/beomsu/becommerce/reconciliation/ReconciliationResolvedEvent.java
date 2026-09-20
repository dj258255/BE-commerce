package com.beomsu.becommerce.reconciliation;

import java.time.Instant;

/**
 * 대사 불일치가 사람에 의해 수기 확정됐다 (ADR-008, ADR-014).
 *
 * <p><b>왜 이벤트인가</b>: 확정 시점에 초안을 만들어 기록하고 싶은데(섀도 모드),
 * 초안 모듈({@code assist})은 이미 {@code reconciliation} 을 의존한다. 반대 방향으로
 * 부르면 순환이 된다. 이벤트는 그 방향을 끊는다 — 대사는 "확정됐다"고 알릴 뿐
 * 누가 듣는지 모른다.
 *
 * <p><b>{@code chosenCause} 가 정답 라벨이다.</b> 사람이 근거를 보고 내린 결론이므로,
 * 규칙이든 모델이든 이걸 기준으로 채점한다.
 *
 * @param reconResultId 대사 결과 id
 * @param orderNo       대상 주문
 * @param chosenCause   사람이 고른 원인
 * @param actor         확정한 사람
 * @param resolvedAt    확정 시각
 */
public record ReconciliationResolvedEvent(long reconResultId, String orderNo,
                                          String chosenCause, String actor,
                                          Instant resolvedAt) {
}
