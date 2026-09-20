package com.beomsu.becommerce.assist.residual;

import com.beomsu.becommerce.reconciliation.ResolveCause;
import com.beomsu.becommerce.reconciliation.CauseSuggestion;
import com.beomsu.becommerce.assist.draft.NumericProvenanceGuard;
import com.beomsu.becommerce.assist.draft.FactPack;
import com.beomsu.becommerce.assist.draft.DraftService;
import com.beomsu.becommerce.reconciliation.CauseSuggestion;
import com.beomsu.becommerce.reconciliation.ResolveCause;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

/**
 * 규칙 분류기가 못 가른 건에만 모델을 붙인다. 여섯 개의 가드를 순서대로 건다.
 *
 * <p><b>1. 규칙이 <i>결정적</i> 후보를 못 냈을 때만 부른다.</b> 원인 아홉 종 가운데 여섯은
 * 산수와 조회로 결정되고, 그건 모델에게 추측시킬 이유가 없다.
 *
 * <p>처음에는 "후보가 하나라도 있으면 안 부른다"로 뒀는데, 엔진으로 표본을 만들어 보니
 * <b>그 문이 거의 닫혀 있었다.</b> 금액 불일치는 수수료도 취소도 아닐 때 배제법으로
 * {@code SUSPECTED_TAMPERING}을 WEAK 로 내는데, 그 근거 문구가 "수수료로도 취소로도
 * <b>설명되지 않는다</b>"이다. 위변조 판정이 아니라 <b>"모르겠다"</b>는 뜻이고,
 * 사람이 다시 봐야 하는 자리가 바로 거기다.
 *
 * <p>그래서 기준을 {@link CauseSuggestion.Confidence#DECISIVE} 유무로 바꿨다. 결정적 후보가
 * 있으면 부르지 않고, WEAK·LIKELY 뿐이면 부른다. 산수가 답한 것은 여전히 모델에게 안 넘긴다.
 *
 * <p><b>2. 출력은 {@link ResolveCause} 안의 값만 받는다.</b> Ramp가 거래 재분류
 * 에이전트에 건 후처리 가드레일과 같다. 목록 밖의 값을 고르면 그 응답을 버린다.
 *
 * <p><b>3. {@link ResolveCause#SUSPECTED_TAMPERING}은 제안 자체를 금지한다.</b>
 * 위변조 의심은 유형으로 배제한다. 이 자리에서 추측이 제일 비싸다.
 *
 * <p><b>4. 신뢰도가 임계 미만이면 기권으로 처리한다.</b> 고위험 분류의 표준 장치인
 * selective prediction 이다. 커버리지를 정확도와 맞바꾼다. {@code OTHER}도 기권으로 친다 —
 * "모르겠다"를 원인 코드로 남기면 집계가 오염된다.
 *
 * <p><b>5. 근거 문장의 숫자는 {@link NumericProvenanceGuard}로 대조한다.</b> 상담 초안과
 * 같은 계약이다. 코드가 낸 값이 아닌 금액·날짜가 하나라도 있으면 제안을 통째로 버린다.
 *
 * <p><b>6. 사실이 불완전하면 아예 부르지 않는다.</b> 타임라인 조회가 한 곳이라도 실패하면
 * 그 사실 묶음으로는 원인을 가릴 수 없다. 프롬프트에 "불완전합니다"라고 적어 줘도
 * <b>세 모델 모두 그걸 무시하고 단정하는 것을 홀드아웃 15건에서 확인했다.</b>
 * 이 가드를 넣자 qwen3:14b 는 4건 중 4건을 기권했고(전 15/15), 8b 와 llama 도
 * 단정이 2건·3건에서 각각 1건으로 줄었다. <b>모델도 프롬프트도 안 바꿨다. 코드가 막았다.</b>
 *
 * <p>이게 이 분야의 표준이기도 하다. 프롬프트로 시키는 기권은 맥락이 부족할 때 실패한다는
 * 것이 보고돼 있고("Prompt-Based Abstention Fails Under Misleading Context"),
 * 가드레일 설계에서도 <b>프롬프트는 행동에 영향을 줄 뿐 강제 가능한 통제 경계를 만들지
 * 못한다</b>고 본다. 모델 가중치는 드리프트하거나 파인튜닝으로 덮이지만 코드의 조건문은
 * 그렇지 않다. 그래서 LLM 호출 <b>전에</b> 도는 결정적 검사를 둔다.
 *
 * <p><b>7. 실측으로 켤 만한 유형만 받는다.</b> 전체 정확도가 아니라 유형별로 정한다.
 * 지금 켜진 것은 {@code INTERNAL_RECORD_LOST} 하나다.
 *
 * <p><b>8. {@code resolve}는 하지 않는다.</b> 이 서비스는 후보를 돌려줄 뿐이고, 확정은
 * 사람이 화면에서 한다. 자동 확정은 그 유형의 실측 오류율이 쌓인 뒤에 따로 결정할 문제다.
 *
 * <p><b>스위치는 {@code app.assist.residual-provider} 하나다.</b> 기본값 {@code template}은
 * 항상 기권하므로 켜 두어도 아무 일이 일어나지 않는다. 상담 초안이 {@code draft-provider}
 * 하나로 같은 일을 한다. 스위치를 둘 두면 어느 쪽이 껐는지 헷갈리고, 켰다고 생각한 채
 * 안 도는 상태가 생긴다.
 *
 * <p><b>{@code ollama}로 바꾸면 즉시 산다.</b> 다만 화면 응답에 모델 왕복이 붙는다 —
 * 실측으로 qwen3:8b 가 2~8초, 14b 가 4~17초였다. 그래서 이 창구를 대사 어드민과
 * 분리해 뒀다. 화면은 규칙 제안을 먼저 그리고 이 후보는 나중에 채우면 된다.
 */
@Service
@RequiredArgsConstructor
public class ResidualCauseService {

    private static final Logger log = LoggerFactory.getLogger(ResidualCauseService.class);

    /** 가드 3 — 모델이 절대 고를 수 없는 원인. */
    static final EnumSet<ResolveCause> FORBIDDEN =
            EnumSet.of(ResolveCause.SUSPECTED_TAMPERING, ResolveCause.OTHER);

    /**
     * 가드 7 — <b>실측으로 켤 만한 유형만 받는다.</b>
     *
     * <p>엔진이 만든 272건에서 유형별로 갈라 재니 결과가 확연히 달랐다. 세 모델
     * (qwen3 8B·14B, llama3.1 8B) 모두에서 {@code INTERNAL_RECORD_LOST}는 60건 중 60건을
     * 맞혔고, {@code PARTIAL_CANCEL_NOT_REFLECTED}는 60건 중 0건이었다. 뒤는 셋 다
     * 차액만 보면 수수료라고 답한다.
     *
     * <p>그래서 전체 정확도(25~40%)로 켜고 끄기를 정하지 않고 <b>유형별로 정한다.</b>
     * Monzo·Revolut 도 잔액 조회와 단순 분쟁만 봇에 맡기고 복잡한 건은 사람에게 넘긴다.
     * Klarna 는 반대로 범위를 넓게 잡았다가 되돌렸다 — 복잡한 분쟁에서 모델이
     * <b>확신에 찬 틀린 답</b>을 냈기 때문이다.
     *
     * <p>이 목록은 실측 결과이므로 <b>모델이나 프롬프트를 바꾸면 다시 재야 한다.</b>
     * 늘리려면 그 유형의 실측 정확도가 먼저 있어야 한다.
     */
    static final EnumSet<ResolveCause> ENABLED =
            EnumSet.of(ResolveCause.INTERNAL_RECORD_LOST);

    /*
     * <b>넓히려다 넓힐 자리가 없다는 것을 확인했다.</b>
     *
     * 규칙 분류기가 못 내는 원인은 아홉 종 중 둘뿐이었다 — DUPLICATE_RECORD 와 OTHER 다.
     * OTHER 는 위 FORBIDDEN 이라 남는 것은 DUPLICATE_RECORD 하나였다.
     *
     * 그런데 그 자리도 모델의 자리가 아니었다. 대사 엔진의 결과 유형은 넷뿐이라
     * (MATCHED·INTERNAL_ONLY·EXTERNAL_ONLY·AMOUNT_MISMATCH) 중복 기록은 별도 유형으로
     * 오지 않고 <b>외부가 내부의 정수배인 AMOUNT_MISMATCH</b> 로 나타난다. 배수인지는 나눗셈이다.
     *
     * 그래서 모델을 켜는 대신 규칙에 넣었다(CauseClassifier ③). 그 전까지 이 건들은 규칙 ④로
     * 떨어져 <b>SUSPECTED_TAMPERING</b> 이 붙고 있었다. PG 파일에 같은 줄이 두 번 실린 것에
     * 위변조 의심이 붙는 상태였다.
     *
     * 결론은 15 문서와 같다. 이 자리에서 모델이 규칙보다 나은 구간을 아직 못 찾았다.
     * 찾으면 그때 넓힌다.
     */

    private final DraftService draftService;
    private final NumericProvenanceGuard numberGuard;
    private final Optional<ResidualCausePort> port;
    private final MeterRegistry registry;

    /** 가드 4 — 이 값 미만이면 기권. */
    @Value("${app.assist.residual.min-confidence:70}")
    private int minConfidence;

    /**
     * 규칙이 못 가른 건에 원인 후보를 제안한다.
     *
     * @param rules 규칙 분류기가 낸 제안. <b>비어 있지 않으면 아무것도 하지 않는다</b>
     * @return 후보. 기권했거나 가드에 걸렸으면 {@link Optional#empty()}
     */
    public Optional<ResidualSuggestion> suggest(String orderNo, Long reconResultId,
                                                List<CauseSuggestion> rules) {
        if (port.isEmpty()) {
            return Optional.empty();
        }
        boolean decided = rules != null && rules.stream()
                .anyMatch(r -> r.confidence() == CauseSuggestion.Confidence.DECISIVE);
        if (decided) {                          // 가드 1
            count("skipped_rules_decided");
            return Optional.empty();
        }

        // 가드 1-1 — 모델이 낼 수 있는 값을 규칙이 <이미> 냈으면 부르지 않는다.
        //
        // 홀드아웃을 정리하다 드러났다. 프롬프트는 켠 유형만 보여주므로 모델의 답은
        // INTERNAL_RECORD_LOST 아니면 기권 둘뿐이고, 가드 8은 그 값을 대사 결과가
        // <외부에만 있음>일 때만 통과시킨다. 그런데 그 조건에서는 규칙 분류기가 이미
        // 같은 값을 낸다("외부에만 존재. 내부에 이 주문의 기록이 없다").
        //
        // 즉 지금 켠 범위에서 모델이 할 수 있는 최선은 <규칙에 동의하는 것>뿐이다.
        // 그 왕복은 값을 만들지 않고 지연과 비용만 만든다.
        if (rules != null && rules.stream().anyMatch(r -> ENABLED.contains(r.cause()))) {
            count("rules_already_cover");
            log.info("[residual] 규칙이 이미 켠 유형을 제안해 모델을 부르지 않음 order={}", orderNo);
            return Optional.empty();
        }

        FactPack facts = draftService.factsFor(orderNo, reconResultId);
        if (facts.facts().isEmpty()) {
            count("no_facts");
            return Optional.empty();
        }
        if (!facts.complete()) {                // 가드 6
            count("incomplete_facts");
            log.info("[residual] 사실이 불완전해 모델을 부르지 않음 order={}", orderNo);
            return Optional.empty();
        }

        Optional<ResidualSuggestion> raw;
        try {
            raw = port.get().suggest(facts);
        } catch (RuntimeException e) {
            count("error");
            log.warn("[residual] 후보 생성 실패 order={} recon={}", orderNo, reconResultId, e);
            return Optional.empty();
        }

        if (raw.isEmpty()) {
            count("abstained");                 // 모델이 스스로 기권
            return Optional.empty();
        }
        ResidualSuggestion s = raw.get();

        if (s.cause() == null || FORBIDDEN.contains(s.cause())) {   // 가드 2·3
            count("forbidden_cause");
            log.info("[residual] 금지된 원인 제안을 버림 order={} cause={}", orderNo, s.cause());
            return Optional.empty();
        }
        if (!ENABLED.contains(s.cause())) {                          // 가드 7
            count("type_not_enabled");
            log.info("[residual] 아직 켜지 않은 유형이라 버림 order={} cause={}", orderNo, s.cause());
            return Optional.empty();
        }
        // 가드 8 — 프롬프트에 적은 판정 기준을 <코드로도> 강제한다.
        //
        // 홀드아웃에서 확인했다. "내부 기록 금액이 있으면 이 원인이 아니다"를 프롬프트에 명시했는데도
        // 내부에만 있는 건(RECON_INTERNAL_ONLY)에서 세 모델이 <45건 중 45건> 모두 INTERNAL_RECORD_LOST 를
        // 냈다. 방향이 정반대인 오답이다.
        //
        // 이 오답은 앞의 가드로 못 막는다. 프롬프트가 켠 유형만 보여주므로 모델이 낼 수 있는 값은
        // INTERNAL_RECORD_LOST 아니면 기권 둘뿐이고, 그래서 <모든 오답이 켠 유형과 같은 값>이다.
        // 가드 7(켠 유형인가)도, 가드 4(신뢰도)도 그냥 통과한다 — 그 오답들의 신뢰도는 95~100 이었다.
        //
        // 그래서 대사 결과 유형을 직접 본다. 외부에만 있는 건이 아니면 이 원인일 수 없다.
        if (s.cause() == ResolveCause.INTERNAL_RECORD_LOST && !facts.internalRecordAbsent()) {
            count("contradicts_recon_result");
            log.info("[residual] 대사 결과가 <외부에만 있음>이 아니라 버림 order={}", orderNo);
            return Optional.empty();
        }
        if (s.confidence() < minConfidence) {                       // 가드 4
            count("below_threshold");
            return Optional.empty();
        }

        List<String> unsourced = numberGuard.verify(s.rationale(), facts);   // 가드 5
        if (!unsourced.isEmpty()) {
            count("unsourced_figures");
            log.info("[residual] 출처 없는 숫자로 제안을 버림 order={} rejected={}", orderNo, unsourced);
            return Optional.empty();
        }

        count("suggested");
        log.info("[residual] 후보 order={} cause={} confidence={} source={}",
                orderNo, s.cause(), s.confidence(), port.get().name());
        return Optional.of(s);
    }

    private void count(String outcome) {
        registry.counter("assist.residual.outcome", "outcome", outcome).increment();
    }
}
