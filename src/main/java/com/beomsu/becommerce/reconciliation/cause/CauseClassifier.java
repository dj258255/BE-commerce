package com.beomsu.becommerce.reconciliation.cause;

import com.beomsu.becommerce.reconciliation.ResolveCause;
import com.beomsu.becommerce.reconciliation.CauseSuggestion;
import com.beomsu.becommerce.reconciliation.internal.ReconciliationResultRepository;
import com.beomsu.becommerce.reconciliation.internal.ReconciliationResult;
import com.beomsu.becommerce.reconciliation.internal.InternalRecord;
import com.beomsu.becommerce.payment.PaymentTimelineFacts;
import com.beomsu.becommerce.payment.PaymentTimelineFacts.PaymentState;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 불일치 원인을 <b>규칙으로</b> 제안한다 (ADR-012).
 *
 * <p><b>왜 AI가 아닌가</b>: 원인 8종 중 6종이 산수나 조회로 결정된다. 차액이 수수료율과
 * 원 단위까지 맞는지, 취소된 금액과 같은지, 인접일에 같은 주문이 있는지 — 전부 계산이다.
 * 여기에 모델을 쓰면 <b>산수가 이미 답하는 것을 추측하게 만드는</b> 셈이고,
 * 남는 위변조 의심은 추측이 가장 위험한 자리다([11 문서](../../../../../../docs/11-AI-운영자동화-검토.md)).
 *
 * <p><b>확정하지 않는다.</b> 제안만 하고 {@code resolve}는 사람이 호출한다.
 * 이건 ADR-008에서 정한 원칙이고, 업계 사례에서도 성공한 프로덕션은 예외 없이
 * 출력을 후보로만 쓴다.
 *
 * <p><b>근거를 함께 낸다.</b> 근거 없는 제안은 사람이 처음부터 다시 조사해야 하므로
 * 확인 비용만 늘린다. 각 규칙은 "왜 그렇게 봤는지"를 숫자로 남긴다.
 */
@Service
public class CauseClassifier {

    /** 거래일 경계로 볼 폭. 자정 기준 앞뒤 이 시간 안의 승인은 날짜가 갈릴 수 있다. */
    private static final Duration BOUNDARY_WINDOW = Duration.ofHours(2);

    private final PaymentTimelineFacts paymentFacts;
    private final ReconciliationResultRepository resultRepository;
    private final long feeBps;

    public CauseClassifier(PaymentTimelineFacts paymentFacts,
                    ReconciliationResultRepository resultRepository,
                    @Value("${app.settlement.fee-bps:270}") long feeBps) {
        this.paymentFacts = paymentFacts;
        this.resultRepository = resultRepository;
        this.feeBps = feeBps;
    }

    /**
     * 원인 후보를 확신 순으로 제안한다. 아무것도 못 가르면 <b>빈 목록</b> —
     * 억지로 하나 고르면 사람이 그것에 끌려간다(앵커링).
     */
    @Transactional(readOnly = true)
    public List<CauseSuggestion> suggest(ReconciliationResult result) {
        List<CauseSuggestion> out = new ArrayList<>();
        switch (result.getResult()) {
            case AMOUNT_MISMATCH -> classifyAmountMismatch(result, out);
            case INTERNAL_ONLY -> classifyInternalOnly(result, out);
            case EXTERNAL_ONLY -> out.add(CauseSuggestion.likely(ResolveCause.INTERNAL_RECORD_LOST,
                    "외부에만 존재. 내부에 이 주문의 기록이 없다"));
            case MATCHED -> { /* 불일치가 아니다 */ }
        }
        out.sort(java.util.Comparator.comparing(CauseSuggestion::confidence));
        return List.copyOf(out);
    }

    /**
     * 금액이 다를 때 — 차액이 무엇과 일치하는지로 가른다.
     *
     * <p><b>규칙 사이에 순서가 있다.</b> 설명이 되는 원인을 찾으면 "설명되지 않는다"는 전제로
     * 만들어진 후보는 내지 않는다. 실측에서 이걸 놓쳐 수수료가 정확히 맞는 건에
     * 위변조 의심이 함께 붙었다 — 근거 문장은 "수수료로도 설명되지 않으면"이라고 써놓고
     * 설명된 경우에도 붙인 것이다. 그러면 사람이 매번 배제 확인을 해야 해서
     * 제안이 오히려 일을 늘린다.
     */
    private void classifyAmountMismatch(ReconciliationResult r, List<CauseSuggestion> out) {
        long internal = r.getInternalAmount();
        long diff = internal - r.getExternalAmount();

        // ① 수수료: 차액이 수수료율과 원 단위까지 맞으면 다른 해석이 없다.
        //    반올림 방식 차이를 흡수하려 ±1원을 허용한다 — 그 이상은 수수료가 아니다.
        long expectedFee = internal * feeBps / 10_000;
        boolean explainedByFee = Math.abs(diff - expectedFee) <= 1;
        if (explainedByFee) {
            out.add(CauseSuggestion.decisive(ResolveCause.FEE_CALCULATION_DIFF,
                    "차액 %,d원 = 내부 %,d원 × %d bps (기대 수수료 %,d원)"
                            .formatted(diff, internal, feeBps, expectedFee),
                    diff, internal, expectedFee));
        }

        // ② 부분취소: 취소된 금액과 차액이 같은지 본다. balance로 결정적으로 계산된다.
        var state = paymentFacts.findState(r.getOrderNo());
        boolean explainedByCancel = state
                .map(p -> p.cancelCount() > 0 && p.canceledAmount() == diff)
                .orElse(false);
        state.ifPresent(p -> {
            if (explainedByCancel) {
                out.add(CauseSuggestion.decisive(ResolveCause.PARTIAL_CANCEL_NOT_REFLECTED,
                        "취소 %d건으로 %,d원이 취소됐고 차액과 정확히 일치. PG 파일이 취소 전 금액을 실었다"
                                .formatted(p.cancelCount(), p.canceledAmount()),
                        p.canceledAmount()));
            }
        });

        // ③ 중복 기록: 외부가 내부의 정수배다. PG 파일에 같은 줄이 두 번 실리면 이렇게 보인다.
        //    산수로 결정되므로 모델에 물을 자리가 아니다. 이걸 안 보면 아래 ④로 떨어져
        //    <b>평범한 파일 중복에 위변조 의심이 붙는다.</b>
        boolean explainedByDuplicate = false;
        if (internal > 0 && diff < 0 && (-diff) % internal == 0) {
            long times = (-diff) / internal + 1;          // 외부가 내부의 몇 배인가
            explainedByDuplicate = true;
            // <b>결정적이 아니라 유력이다.</b> 같은 주문에 결제가 실제로 두 번 일어났는데
            // 우리가 하나만 기록한 경우도 같은 숫자가 된다. 그 둘은 산수로 안 갈린다.
            out.add(CauseSuggestion.likely(ResolveCause.DUPLICATE_RECORD,
                    "외부 %,d원이 내부 %,d원의 정확히 %d배다. PG 파일에 같은 건이 %d번 실렸을 수 있다. "
                            .formatted(r.getExternalAmount(), internal, times, times)
                            + "다만 실제로 결제가 %d번 일어났는데 내부에 1건만 남은 경우도 같은 숫자가 된다"
                            .formatted(times),
                    internal, r.getExternalAmount()));
        }

        // ④ 남은 것: 위 어느 것으로도 설명되지 않을 때만 의심을 제기한다.
        //    설명이 있는데도 이걸 붙이면 사람이 매번 배제 확인을 해야 한다.
        if (!explainedByFee && !explainedByCancel && !explainedByDuplicate && diff != 0) {
            String cancelNote = state
                    .map(p -> p.cancelCount() == 0
                            ? "취소 이력이 없다"
                            : "취소 %,d원이 있지만 차액과 다르다".formatted(p.canceledAmount()))
                    .orElse("결제 기록을 찾지 못했다");
            java.util.Set<Long> figures = new java.util.LinkedHashSet<>(
                    java.util.List.of(Math.abs(diff), Math.abs(expectedFee)));
            state.filter(p -> p.cancelCount() > 0)
                    .ifPresent(p -> figures.add(Math.abs(p.canceledAmount())));
            out.add(new CauseSuggestion(ResolveCause.SUSPECTED_TAMPERING,
                    CauseSuggestion.Confidence.WEAK,
                    "차액 %,d원이 수수료(%,d원)로도 취소로도 설명되지 않는다. %s"
                            .formatted(diff, expectedFee, cancelNote),
                    figures));
        }
    }

    /** 내부에만 있을 때 — 아직 안 온 것인지, 영영 안 올 것인지. */
    private void classifyInternalOnly(ReconciliationResult r, List<CauseSuggestion> out) {
        LocalDate tradeDate = r.getTradeDate();

        // 같은 주문이 <다른 거래일> 파일에 외부 기록으로 잡혔는가.
        // 잡혔다면 "안 온 것"이 아니라 "다른 날로 간 것"이므로, 어느 쪽인지를 가른다.
        List<ReconciliationResult> elsewhere =
                resultRepository.findByOrderNoOrderByIdAsc(r.getOrderNo()).stream()
                        .filter(o -> o.getExternalAmount() != null)
                        .filter(o -> !o.getTradeDate().equals(tradeDate))
                        .toList();

        if (!elsewhere.isEmpty()) {
            // 인접일(±1)에 잡혔고 승인이 자정 근처였으면 <경계>다. 파일이 늦은 게 아니라
            // 같은 거래를 양쪽이 다른 날짜로 센 것이다. 대응이 다르다 —
            // 지연은 기다리면 맞춰지지만, 경계는 기준을 고쳐야 한다.
            boolean adjacent = elsewhere.stream().anyMatch(
                    o -> Math.abs(ChronoUnit.DAYS.between(tradeDate, o.getTradeDate())) == 1);
            Optional<Instant> approvedAt =
                    paymentFacts.findState(r.getOrderNo()).map(PaymentState::requestedAt);

            if (adjacent && approvedAt.filter(CauseClassifier::nearMidnight).isPresent()) {
                out.add(CauseSuggestion.decisive(ResolveCause.TIMEZONE_BOUNDARY,
                        "인접 거래일에 같은 주문이 외부 기록으로 있고, 승인 시각 %s이 자정에서 %d시간 안이다. "
                                .formatted(approvedAt.orElseThrow(), BOUNDARY_WINDOW.toHours())
                                + "같은 거래를 양쪽이 다른 날짜로 셌을 가능성이 높다"));
                return;
            }
            if (elsewhere.stream().anyMatch(o -> o.getTradeDate().isAfter(tradeDate))) {
                out.add(CauseSuggestion.decisive(ResolveCause.PG_FILE_DELAY,
                        "이후 거래일 파일에 같은 주문이 외부 기록으로 잡혔다. 파일 도착이 늦었을 뿐이다"));
                return;
            }
        }
        paymentFacts.findState(r.getOrderNo()).ifPresent(p -> {
            if (p.cancelCount() > 0) {
                out.add(CauseSuggestion.likely(ResolveCause.NET_CANCEL_TIMING,
                        "취소 %d건이 있다. 승인 직후 취소되어 PG 정산 대상에서 빠졌을 수 있다"
                                .formatted(p.cancelCount())));
            } else {
                out.add(CauseSuggestion.likely(ResolveCause.PG_FILE_DELAY,
                        "승인은 %s에 있었고 취소 이력이 없다. 아직 파일에 안 실렸을 가능성이 높다"
                                .formatted(p.requestedAt())));
            }
        });
    }

    /**
     * 승인 시각이 거래일 경계(자정) 근처인가.
     *
     * <p>경계 문제는 <b>자정 근처 승인에서만</b> 생긴다. 낮 2시 거래가 날짜를 넘어갈 일은 없다.
     * 이 조건을 안 걸면 인접일에 기록이 있다는 이유만으로 전부 경계로 몰려, 정작 흔한
     * 파일 지연을 경계로 잘못 부른다.
     */
    private static boolean nearMidnight(Instant at) {
        LocalTime t = at.atZone(InternalRecord.TRADE_ZONE).toLocalTime();
        Duration sinceMidnight = Duration.ofSeconds(t.toSecondOfDay());
        Duration untilMidnight = Duration.ofDays(1).minus(sinceMidnight);
        return sinceMidnight.compareTo(BOUNDARY_WINDOW) <= 0
                || untilMidnight.compareTo(BOUNDARY_WINDOW) <= 0;
    }
}
