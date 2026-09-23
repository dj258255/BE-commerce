package com.beomsu.becommerce.assist.draft;

import com.beomsu.becommerce.reconciliation.CauseSuggestion;
import com.beomsu.becommerce.reconciliation.CauseSuggestion;
import com.beomsu.becommerce.reconciliation.ReconciliationAdminService;
import com.beomsu.becommerce.timeline.OrderTimeline;
import com.beomsu.becommerce.timeline.OrderTimelineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 초안 생성의 전 과정을 잇는다 — 사실 조립 → 초안 → 검증.
 *
 * <p><b>순서가 설계다.</b> 검증을 마지막에 두는 게 아니라, 검증을 통과하지 못한 초안이
 * 이 메서드 밖으로 나가지 못하게 한다. 화면이 "검증 실패지만 참고용으로 보여주기"를
 * 고를 수 없어야 한다 — 사람은 눈앞의 문장에 끌려가고(앵커링), 그 문장이 틀렸다는 걸
 * 확인할 방법은 어차피 없다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DraftService {

    private final OrderTimelineService timelineService;
    private final ReconciliationAdminService reconciliationAdmin;
    private final DraftPort draftPort;
    private final PromptBuilder prompts;
    private final NumericProvenanceGuard numberGuard;
    private final CustomerGlossary glossary;
    private final DraftRubric rubric;
    /** 금액 결손은 <b>루브릭이 안 보던 자리</b>다. 초안에 빠진 금액을 되묻는 근거로 쓴다. */
    private final AmountCoverageGuard coverageGuard;
    /** 대조군. {@code draft-provider} 와 무관하게 항상 있다({@link #baselineFor}). */
    private final TemplateDraftAdapter template;
    /** 심판은 선택이다 — 켜지 않으면 없다. 켜면 호출이 한 번 더 는다. */
    private final java.util.Optional<DraftJudge> judge;

    /**
     * <b>대조군 초안.</b> {@code draft-provider} 가 무엇이든 템플릿으로 만든다.
     *
     * <p>블라인드 리뷰의 활성화 조건이 "편집률 중앙값이 <b>템플릿보다</b> 낮을 것"이라,
     * 모델 초안과 <b>같은 사실에서 같은 시점에</b> 템플릿 초안도 있어야 한다.
     * provider 를 바꿔 두 번 돌리면 두 번째 회차는 리뷰어가 이미 답을 아는 상태다.
     *
     * <p>되묻기와 심판은 태우지 않는다. 대조군은 <b>지금 운영에서 쓰는 그것</b>이어야 하고,
     * 템플릿은 모델을 안 부르므로 고칠 것도 없다.
     */
    @Transactional(readOnly = true)
    public CsDraft baselineFor(String orderNo, Long reconResultId) {
        OrderTimeline timeline = timelineService.assemble(orderNo);
        FactPack facts = FactPack.from(timeline, causeHint(reconResultId));
        if (facts.empty()) {
            return CsDraft.none(orderNo, template.name(), timeline.complete());
        }
        Optional<String> text = template.draft(facts);
        if (text.isEmpty()) {
            return CsDraft.none(orderNo, template.name(), timeline.complete());
        }
        // 템플릿은 사실을 옮겨 적을 뿐이라 숫자 출처 검증에 걸릴 일이 없다. 그래도 같은
        // 잣대로 채점해 둔다 — 대조군만 채점을 건너뛰면 비교가 한쪽에만 유리해진다.
        return CsDraft.ok(orderNo, text.get(), template.name(), timeline.complete(),
                glossary.findJargon(text.get()), rubric.score(text.get(), facts),
                DraftJudge.Verdict.unavailable("대조군은 심판을 안 태운다"));
    }

    /**
     * 주문 한 건의 상담 초안을 만든다.
     *
     * @param orderNo       대상 주문
     * @param reconResultId 대사 불일치 id. 주면 규칙 분류기의 원인 제안을 함께 싣는다.
     *                      없으면 null — <b>원인을 모르면 모르는 채로 둔다</b>
     */
    @Transactional(readOnly = true)
    public CsDraft draftFor(String orderNo, Long reconResultId) {
        OrderTimeline timeline = timelineService.assemble(orderNo);
        FactPack facts = FactPack.from(timeline, causeHint(reconResultId));

        if (facts.empty()) {
            return CsDraft.none(orderNo, draftPort.name(), timeline.complete());
        }

        Optional<String> text = draftPort.draft(facts);
        if (text.isEmpty()) {
            return CsDraft.none(orderNo, draftPort.name(), timeline.complete());
        }

        // 루브릭이 짚은 것만 짧은 프롬프트로 한 번 고쳐본다(ADR-014).
        // <절대 나빠지지 않는다> — 고친 것이 원본보다 못하면 원본을 쓴다.
        String best = reviseIfWorthIt(facts, text.get());

        List<String> rejected = numberGuard.verify(best, facts);
        if (!rejected.isEmpty()) {
            // 버리되 조용히 버리지 않는다. 어느 구현이 무슨 값을 지어냈는지가
            // 그 구현을 계속 쓸지 판단하는 유일한 근거다.
            log.warn("초안 검증 실패 — 버림. orderNo={} source={} 문제={}",
                    orderNo, draftPort.name(), rejected);
            return CsDraft.rejected(orderNo, draftPort.name(), rejected, timeline.complete());
        }
        // 용어 누출은 <버리지 않고> 표시한다. 틀린 게 아니라 다듬을 문제다.
        // 세어서 남기는 이유는 프롬프트를 고쳤을 때 나아졌는지 알기 위해서다.
        List<String> jargon = glossary.findJargon(best);
        if (!jargon.isEmpty()) {
            log.info("초안에 내부 용어 남음 order={} source={} 용어={}",
                    orderNo, draftPort.name(), jargon);
        }
        // 정답 없이 채점한다(reference-free). 버리지 않고 실어 보낸다 —
        // 상담원이 무엇이 빠졌는지 보고 판단할 근거가 된다.
        DraftRubric.Score score = rubric.score(best, facts);
        if (!score.failed().isEmpty()) {
            log.info("초안 루브릭 {}/{} order={} 미충족={}",
                    score.passed(), score.total(), orderNo, score.failed());
        }
        // 코드가 못 잡는 것 — <근거 없는 단정> — 을 다른 계열 모델에게 묻는다.
        // 버리지 않는다. 심판도 틀릴 수 있어서, 사람이 보고 판단할 표시로만 쓴다.
        DraftJudge.Verdict verdict = judge
                .flatMap(j -> j.judge(facts, best))
                .orElseGet(() -> DraftJudge.Verdict.unavailable("심판 없음"));
        if (!verdict.grounded()) {
            log.info("[judge] 근거 없는 단정 order={} judge={} 문장={} 이유={}",
                    orderNo, verdict.judge(), verdict.quote(), verdict.why());
        }
        return CsDraft.ok(orderNo, best, draftPort.name(), timeline.complete(),
                jargon, score, verdict);
    }


    /**
     * 루브릭이 지적한 것만 한 번 고쳐본다. <b>고친 것이 원본보다 못하면 원본을 돌려준다.</b>
     *
     * <p><b>이 보장이 핵심이다.</b> 체이닝은 앞 단계 오류가 뒤로 번질 수 있고,
     * 단계별 정확도가 높아도 전체 정확도는 낮아질 수 있다. 그래서 두 초안을
     * <b>같은 잣대로 채점해 나은 쪽을 고른다</b> — 최악이라도 한 번 더 부른 비용만 든다.
     *
     * <p>지적이 없으면 부르지 않는다. 고칠 게 없는데 다시 쓰게 하면 멀쩡한 문장이 흔들린다.
     */
    private String reviseIfWorthIt(FactPack facts, String original) {
        DraftRubric.Score before = rubric.score(original, facts);
        // 루브릭은 <분류기 근거의 첫 금액> 하나만 본다. 사실 목록에 있는 나머지 금액이
        // 통째로 빠져도 통과한다. 실측에서 12건 중 8건이 이 자리였다(13 문서 실험 14).
        List<String> issues = new java.util.ArrayList<>(before.failed());
        List<Long> missing = coverageGuard.missing(original, facts);
        for (Long a : missing) {
            issues.add("금액 " + java.text.NumberFormat.getNumberInstance(java.util.Locale.KOREA).format(a)
                    + "원이 초안에 없다");
        }
        if (issues.isEmpty()) {
            return original;
        }
        Optional<String> revised = draftPort.revise(facts, original, issues);
        if (revised.isEmpty() || revised.get().isBlank()) {
            return original;
        }
        // 지어낸 값이 들어갔으면 그것만으로 탈락이다. 루브릭 점수와 무관하게 버린다.
        if (!numberGuard.verify(revised.get(), facts).isEmpty()) {
            log.info("수정본이 검증에 걸려 원본 유지 order={}", facts.orderNo());
            return original;
        }
        DraftRubric.Score after = rubric.score(revised.get(), facts);
        int missingAfter = coverageGuard.missing(revised.get(), facts).size();
        // <절대 나빠지지 않는다>: 루브릭이 오르거나, 루브릭이 같은데 빠진 금액이 줄었을 때만 바꾼다
        boolean better = after.passed() > before.passed()
                || (after.passed() == before.passed() && missingAfter < missing.size());
        if (better) {
            log.info("수정으로 개선 order={} 루브릭 {}/{} -> {}/{} · 빠진 금액 {} -> {}",
                    facts.orderNo(), before.passed(), before.total(),
                    after.passed(), after.total(), missing.size(), missingAfter);
            return revised.get();
        }
        log.info("수정이 나아지지 않아 원본 유지 order={} 루브릭 {}/{} -> {}/{} · 빠진 금액 {} -> {}",
                facts.orderNo(), before.passed(), before.total(),
                after.passed(), after.total(), missing.size(), missingAfter);
        return original;
    }

    /**
     * 이 건의 프롬프트를 <b>그대로</b> 꺼낸다 — 사람이 다른 곳에서 모델을 돌려보려고.
     *
     * <p><b>왜 필요한가</b>: 앱이 부를 수 있는 모델은 지금 로컬뿐이다. 생성형 AI 는 아직
     * 금융권 망분리 예외가 아니고, 외부 API 를 켜려면 키가 필요하다.
     * 그런데 <b>더 좋은 모델이 어떤 초안을 쓰는지</b>는 궁금하고, 그걸 보는 데
     * 앱이 직접 호출할 필요는 없다.
     *
     * <p>프롬프트를 꺼내 어디서든 돌리고, 받은 초안을 {@link #scoreImported} 로 되돌리면
     * <b>같은 잣대</b>로 채점된다. 앱에 키를 넣지 않고도 모델을 비교할 수 있다.
     *
     * <p><b>주의</b>: 꺼낸 프롬프트에는 주문번호·금액·거래일이 들어 있다.
     * 어디에 붙여 넣는지는 그걸 꺼낸 사람의 책임이다 — 앱은 데이터를 내보내지 않지만,
     * 사람이 내보낼 수는 있다.
     */
    @Transactional(readOnly = true)
    public ExportedPrompt exportPrompt(String orderNo, Long reconResultId) {
        FactPack facts = factsFor(orderNo, reconResultId);
        return new ExportedPrompt(orderNo, prompts.system(facts), prompts.user(facts),
                facts.facts().size());
    }

    /**
     * 밖에서 받아온 초안을 <b>같은 검사</b>로 채점한다. 저장하지 않는다.
     *
     * <p>여기가 없으면 "저 모델이 더 잘 쓰더라"가 인상으로만 남는다.
     * 숫자 검증·용어·루브릭을 그대로 태워야 <b>비교</b>가 된다.
     *
     * @param source 어디서 받았는지. 화면에 그대로 실린다 — 출처 없는 초안을 만들지 않는다
     */
    @Transactional(readOnly = true)
    public CsDraft scoreImported(String orderNo, Long reconResultId, String text, String source) {
        FactPack facts = factsFor(orderNo, reconResultId);
        String label = "manual:" + (source == null || source.isBlank() ? "unknown" : source.trim());

        if (text == null || text.isBlank()) {
            return CsDraft.none(orderNo, label, facts.complete());
        }
        List<String> rejected = numberGuard.verify(text, facts);
        if (!rejected.isEmpty()) {
            return CsDraft.rejected(orderNo, label, rejected, facts.complete());
        }
        // 심판도 켜져 있으면 같이 태운다. 밖에서 온 초안이라고 봐주지 않는다 —
        // 오히려 이 경로가 <다른 모델을 비교하는 자리>라 잣대가 같아야 의미가 있다.
        DraftJudge.Verdict verdict = judge
                .flatMap(j -> j.judge(facts, text))
                .orElseGet(() -> DraftJudge.Verdict.unavailable("심판 없음"));

        return CsDraft.ok(orderNo, text, label, facts.complete(),
                glossary.findJargon(text), rubric.score(text, facts), verdict);
    }

    /**
     * 꺼낸 프롬프트.
     *
     * @param factCount 사실이 몇 줄인지. 0이면 프롬프트를 돌려봐야 나올 게 없다
     */
    public record ExportedPrompt(String orderNo, String system, String user, int factCount) {
    }

    /**
     * 사실 묶음만 만든다 — <b>초안은 만들지 않는다.</b>
     *
     * <p>블라인드 리뷰 1단계가 이걸 쓴다. 사람이 사실만 보고 자기 답을 먼저 써야 하는데,
     * 초안을 만들어 두면 응답 어딘가에 실릴 유혹이 생긴다. <b>만들지 않는 것</b>이
     * 가장 확실한 차단이다.
     */
    @Transactional(readOnly = true)
    public FactPack factsFor(String orderNo, Long reconResultId) {
        return FactPack.from(timelineService.assemble(orderNo), causeHint(reconResultId));
    }

    /**
     * 규칙 분류기의 최상위 제안을 한 줄로. 실패해도 초안은 만든다 —
     * 원인 힌트는 있으면 좋은 것이지 없으면 못 만드는 것이 아니다.
     */
    private CauseSuggestion causeHint(Long reconResultId) {
        if (reconResultId == null) {
            return null;
        }
        try {
            List<CauseSuggestion> suggestions = reconciliationAdmin.suggestCauses(reconResultId);
            if (suggestions.isEmpty()) {
                return null;
            }
            return suggestions.get(0);
        } catch (RuntimeException e) {
            log.warn("원인 제안 조회 실패 reconResultId={}", reconResultId, e);
            return null;
        }
    }
}
