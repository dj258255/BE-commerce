package com.beomsu.becommerce.assist.fraudreview;

import com.beomsu.becommerce.assist.draft.FactPack;
import com.beomsu.becommerce.assist.draft.AmountCoverageGuard;
import com.beomsu.becommerce.assist.draft.NumericProvenanceGuard;
import com.beomsu.becommerce.fraud.FraudReviewFacts;
import com.beomsu.becommerce.fraud.FraudReviewFactsPort;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 심사 초안을 만들어 준다. <b>가드에 걸리면 버리고 템플릿으로 떨어뜨린다.</b>
 *
 * <p>구조는 {@code assist.draft.DraftService} 와 같다. 그쪽에서 이미 겪은 것을 그대로 가져왔다.
 *
 * <pre>
 *   FraudReviewFacts      ← 코드가 조립한 사실. 문장 없음
 *         ↓
 *   FraudReviewDraftPort  ← 템플릿이든 모델이든 여기서 갈린다
 *         ↓
 *   NumericProvenanceGuard ← 초안의 금액·날짜가 사실에 있는지 대조
 *         ↓
 *   심사자가 읽고 승인·거부를 누른다
 * </pre>
 *
 * <p><b>왜 가드를 다시 쓰나.</b> 지어낸 금액은 근거 있는 금액과 문장에서 구별되지 않는다.
 * "결제 320,000원" 과 "결제 302,000원" 은 읽는 사람에게 똑같이 그럴듯하다. 심사자도 그 숫자가
 * 어디서 왔는지 모르므로 사람 검토에 맡길 수 없다.
 *
 * <p><b>가드가 잡는 것은 출처뿐이다.</b> 초안에 나온 숫자가 코드가 낸 값인지만 본다. 그 숫자가
 * 맞는 주장에 쓰였는지는 못 본다. 상황 3.3 에서 확인한 대로 <b>가드를 다 통과하고도 틀린 주장이
 * 나올 수 있다.</b> 그래서 판정은 사람이 누른다는 전제가 여기서도 유지된다.
 */
@Slf4j
@Service
public class FraudReviewDraftService {

    /** 알림이 보는 이름. {@code assist.incident.outcome} 과 규칙을 맞춘다. */
    public static final String METRIC = "assist.fraud_review.outcome";

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final FraudReviewFactsPort facts;
    private final FraudReviewDraftPort primary;
    private final TemplateFraudReviewAdapter template;
    private final NumericProvenanceGuard guard;
    private final AmountCoverageGuard coverage;
    private final FactWideningGuard widening;
    private final MeterRegistry registry;

    /**
     * 화면에 나갈 구현을 <b>이름으로</b> 고른다.
     *
     * <p>예전에는 모델 어댑터에 {@code @Primary} 를 붙여 주입으로 갈랐는데, 그러면
     * <b>모델이 빈으로 떠 있다는 사실만으로 화면이 바뀐다.</b> 블라인드 비교 표본을 쌓으려면
     * 모델을 돌려야 하는데, 그것 때문에 심사 화면까지 바뀌면 켤 근거를 모으기도 전에 켜 버리는
     * 셈이 된다. 그래서 <b>도는 것</b>과 <b>나가는 것</b>을 갈랐다.
     */
    public FraudReviewDraftService(FraudReviewFactsPort facts,
                                   List<FraudReviewDraftPort> ports,
                                   TemplateFraudReviewAdapter template,
                                   NumericProvenanceGuard guard,
                                   AmountCoverageGuard coverage,
                                   FactWideningGuard widening,
                                   MeterRegistry registry,
                                   @Value("${app.assist.fraud-review-provider:template}") String provider) {
        this.facts = facts;
        this.template = template;
        this.guard = guard;
        this.coverage = coverage;
        this.widening = widening;
        this.registry = registry;
        this.primary = ports.stream()
                .filter(p -> p.name().startsWith(provider))
                .findFirst()
                .orElse(template);   // 이름이 안 맞으면 템플릿이다. 없는 모델을 부르는 것보다 낫다
    }

    /**
     * 심사 한 건의 초안.
     *
     * @return 초안과 출처. 그런 심사가 없으면 {@link Optional#empty()} —
     *         <b>초안을 못 만든 것과 심사가 없는 것은 다른 사실이다</b>
     */
    public Optional<FraudReviewDraft> draftFor(long reviewId) {
        return facts.factsOf(reviewId).map(this::build);
    }

    private FraudReviewDraft build(FraudReviewFacts f) {
        Optional<String> text = primary.draft(f);

        if (text.isEmpty()) {
            return fallback(f, "no_draft");
        }

        text = guarded(primary, f, text.get());
        if (text.isEmpty()) {
            return fallback(f, "guard_rejected");
        }

        count("ok", primary.name());
        return new FraudReviewDraft(f.reviewId(), text.get(), primary.name(), List.of());
    }

    /**
     * 어느 포트가 만든 초안이든 <b>화면에 나갈 때와 같은 처리를 태운다</b>. 폴백은 안 한다.
     *
     * <p><b>블라인드 비교가 이걸 부른다.</b> 전에는 비교가 포트를 직접 불러 <b>가드를 안 거친
     * 날것</b>을 재고 있었다. 그러면 "모델이 낫다"가 나와도 켠 뒤 화면에는 다른 문장이 나간다.
     * 재는 것과 나가는 것이 달랐다.
     *
     * <p><b>여기서 템플릿으로 안 떨어뜨리는 것이 요점이다.</b> 폴백까지 태우면 모델 쪽이
     * 가드에 걸릴 때 템플릿이 들어가 <b>템플릿 대 템플릿</b>을 비교하게 된다. 그건 비교를
     * 안 한 것인데 했다고 착각하는 형태다. 비면 비운 채로 돌려주고 부르는 쪽이 공개를 접는다.
     */
    Optional<String> guarded(FraudReviewDraftPort port, FraudReviewFacts f, String original) {
        String text = reviseIfAmountMissing(port, f, original);
        // 사실을 넓혀 말한 자리. 숫자가 맞아 출처 검증에는 안 걸린다.
        List<String> widened = widening.verify(text, f);
        if (!widened.isEmpty()) {
            log.warn("[fraud-review] 사실을 넓혀 쓴 초안을 버립니다 review={} port={} bad={}",
                    f.reviewId(), port.name(), widened);
            count("widened", port.name());
            return Optional.empty();
        }
        List<String> bad = guard.verify(text, allowed(f));
        if (!bad.isEmpty()) {
            // 걸린 값을 로그에 남긴다. 무엇을 지어냈는지 안 남기면 프롬프트를 못 고친다.
            log.warn("[fraud-review] 출처 없는 값으로 초안을 버립니다 review={} port={} bad={}",
                    f.reviewId(), port.name(), bad);
            return Optional.empty();
        }
        return Optional.of(text);
    }

    /** 포트가 초안을 만들고 같은 처리를 태운다. 비교 쪽이 부르는 입구다. */
    public Optional<String> guardedDraft(FraudReviewDraftPort port, FraudReviewFacts f) {
        return port.draft(f).flatMap(t -> guarded(port, f, t));
    }

    /**
     * 심사 금액이 초안에 없으면 한 번 되묻는다.
     *
     * <p><b>출처 검증과 방향이 반대다.</b> {@code NumericProvenanceGuard} 는 <b>없는 숫자를
     * 지어냈는지</b>를 보고, 이쪽은 <b>있는 숫자를 버렸는지</b>를 본다. 지어낸 값은 버리면 되는데
     * 빠뜨린 값은 버려도 안 생기므로 다시 쓰게 해야 한다.
     *
     * <p><b>왜 붙였는지는 재고 알았다.</b> 심사 열두 건에 초안을 뽑아 보니 둘이 금액을 통째로
     * 빠뜨린 채 출처 검증을 통과했다. 심사자가 제일 먼저 봐야 할 것이 그 금액인데 거기서 빠진다.
     * 상황 5.2 의 상담 초안에서 같은 구멍을 같은 방법으로 막았다.
     *
     * <p><b>나빠지면 원본을 쓴다.</b> 수정본이 지어낸 값을 넣었거나 빠진 금액이 안 줄었으면
     * 그대로 둔다. 되묻기가 멀쩡한 문장을 흔드는 쪽으로 가면 안 된다.
     */
    private String reviseIfAmountMissing(FraudReviewDraftPort port, FraudReviewFacts f, String original) {
        var facts = allowed(f);
        List<Long> missing = coverage.missing(original, facts);
        if (missing.isEmpty()) {
            return original;
        }
        List<String> issues = missing.stream()
                .map(a -> "금액 " + java.text.NumberFormat.getNumberInstance(java.util.Locale.KOREA).format(a)
                        + "원이 초안에 없다")
                .toList();
        Optional<String> revised = port.revise(f, original, issues);
        if (revised.isEmpty() || revised.get().isBlank()) {
            count("amount_missing_not_revised", port.name());
            return original;
        }
        if (!guard.verify(revised.get(), facts).isEmpty()) {
            log.info("[fraud-review] 수정본이 출처 검증에 걸려 원본 유지 review={}", f.reviewId());
            count("amount_missing_not_revised", port.name());
            return original;
        }
        int after = coverage.missing(revised.get(), facts).size();
        if (after >= missing.size()) {
            count("amount_missing_not_revised", port.name());
            return original;
        }
        log.info("[fraud-review] 되묻기로 금액을 채웠습니다 review={} 빠진 금액 {} -> {}",
                f.reviewId(), missing.size(), after);
        count("amount_missing_revised", port.name());
        return revised.get();
    }

    /**
     * 템플릿으로 떨어뜨린다. <b>그 템플릿도 같은 가드를 통과해야 나간다.</b>
     *
     * <p>템플릿은 사실만 옮기니 통과가 당연한데, 그래서 더 걸어야 한다. 템플릿이 바뀌어
     * 사실에 없는 값을 넣기 시작하면 그때 걸려야 한다.
     */
    private FraudReviewDraft fallback(FraudReviewFacts f, String reason) {
        count(reason, template.name());
        Optional<String> fb = template.draft(f);
        if (fb.isEmpty()) {
            return new FraudReviewDraft(f.reviewId(), null, "none", List.of(reason, "template_empty"));
        }
        List<String> bad = guard.verify(fb.get(), allowed(f));
        if (!bad.isEmpty()) {
            log.error("[fraud-review] 템플릿이 가드에 걸렸습니다 review={} bad={}", f.reviewId(), bad);
            return new FraudReviewDraft(f.reviewId(), null, "none", List.of(reason, "template_rejected"));
        }
        return new FraudReviewDraft(f.reviewId(), fb.get(), template.name(), List.of(reason));
    }

    /**
     * 가드가 허용할 값의 전부.
     *
     * <p>{@code NumericProvenanceGuard} 는 <b>{@code 원} 이 붙은 금액과 날짜만</b> 검사한다.
     * 점수와 비율은 {@code 원} 이 없어 그대로 지나간다. 그 경계를 넓히면 "규칙 3개" 같은
     * 세는 말까지 반려되고, 좁히면 지어낸 금액이 샌다.
     */
    /**
     * 초안이 인용해도 되는 사실.
     *
     * <p><b>규칙 근거에 적힌 숫자도 사실이다.</b> 결제 금액만 넣어 뒀더니 임계 근처 규칙에서
     * 모델이 "100만원에 가깝다"고 쓰는 족족 가드에 걸렸다. 열두 건 중 넷이 그랬다. 그 값은
     * 지어낸 것이 아니라 {@code NEAR_THRESHOLD(980000/1000000)} 처럼 근거 문자열에 이미
     * 우리가 실어 보낸 것이다. 인용을 막을 이유가 없다.
     *
     * <p>근거에서 뽑는 것은 <b>숫자뿐이다.</b> 규칙 이름은 금액이 아니므로 안 넣는다.
     */
    private FactPack allowed(FraudReviewFacts f) {
        Set<Long> amounts = new LinkedHashSet<>();
        amounts.add(f.amount());
        for (var rule : f.firedRules()) {
            if (rule.detail() == null) {
                continue;
            }
            for (String token : rule.detail().split("[^0-9]+")) {
                if (!token.isEmpty() && token.length() <= 18) {
                    amounts.add(Long.parseLong(token));
                }
            }
        }
        Set<LocalDate> dates = new LinkedHashSet<>();
        if (f.detectedAt() != null) {
            dates.add(f.detectedAt().atZone(KST).toLocalDate());
        }
        return new FactPack(f.orderNo(), List.of(), amounts, dates, null, true);
    }

    private void count(String outcome, String source) {
        Counter.builder(METRIC)
                .tag("outcome", outcome)
                .tag("source", source)
                .register(registry)
                .increment();
    }
}
