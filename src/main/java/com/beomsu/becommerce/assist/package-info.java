/**
 * 상담 답변 초안 모듈 (ADR-014). <b>사실은 코드가, 문장만 모델이.</b>
 *
 * <p><b>왜 이 자리인가</b>: 대사 원인 8종 중 6종은 산수로 결정된다. 거기에 모델을 쓰면
 * 이미 결정된 것을 추측하게 만드는 셈이다. 반대로 "이 상황을 고객이 읽을 문장으로 바꾸기"는
 * 코드가 못 하고 모델이 잘한다. 그래서 남은 자리가 여기다.
 * 국내에도 프로덕션 사례가 있다 — 토스뱅크 상담 어시스턴트가 실시간 요약과 <b>응답 초안</b>을
 * 상담원에게 제공하는 형태다.
 *
 * <p><b>모델을 아직 부르지 않는다.</b> 2026-04-20 시행 전자금융감독규정시행세칙 개정으로
 * 금융회사 내부 업무망의 SaaS 이용은 열렸지만, <b>생성형 AI는 아직 망분리 예외가 아니다</b>
 * (당국이 "향후 추진 계획"이라고 밝힌 단계). 그래서 포트만 정의하고 기본 구현은
 * 외부 호출이 없는 {@link TemplateDraftAdapter}로 둔다. 규제가 열리면 어댑터 하나가 늘어난다.
 *
 * <p><b>구조</b>:
 * <pre>
 *   OrderTimeline + CauseSuggestion   ← 결정적으로 조립 (ADR-011, ADR-012)
 *           ↓
 *      FactPack                       ← 숫자·시각·상태만. 문장 없음
 *           ↓
 *      DraftPort                      ← 구현체가 템플릿이든 모델이든 여기서 갈린다
 *           ↓
 *      NumericProvenanceGuard                    ← 초안의 모든 숫자가 FactPack에 있는지 대조
 *           ↓
 *      상담원이 검토·수정 후 발송      ← 초안은 고객에게 직접 나가지 않는다
 * </pre>
 *
 * <p><b>{@code NumericProvenanceGuard}가 이 모듈의 요점이다.</b> 모델은 "매출이 23% 증가해 42억"처럼
 * 근거 있는 문장과 구별되지 않는 숫자를 만들어낸다. 그래서 출처 시스템에 없는 숫자가
 * 나오면 <b>초안을 버린다</b>. 프로덕션에서 쓰이는 방식이고(출처에 없는 값은 flag 또는 reject),
 * Uber Genie도 같은 계열이다 — 주어진 sub-context 안에서만 답하고 출처 URL을 함께 내게 강제한다.
 *
 * <p><b>읽기 전용</b>: 상태를 바꾸지 않는다. 초안 생성이 대사 결과를 건드리면
 * 사람 확인 원칙(ADR-008)을 우회하는 뒷문이 된다.
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = { "shared", "timeline", "reconciliation", "fraud" }
)
package com.beomsu.becommerce.assist;

import com.beomsu.becommerce.timeline.OrderTimeline;
import com.beomsu.becommerce.reconciliation.CauseSuggestion;
import com.beomsu.becommerce.assist.draft.TemplateDraftAdapter;
import com.beomsu.becommerce.assist.draft.NumericProvenanceGuard;
import com.beomsu.becommerce.assist.draft.FactPack;
import com.beomsu.becommerce.assist.draft.DraftPort;
