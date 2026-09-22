package com.beomsu.becommerce.settlement.internal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 내부 정산 기대치와 외부 지급 report를 결정적으로 비교한다.
 *
 * <p>이 클래스는 실제 은행·PG API를 호출하지 않는다. 외부 report를 이미 정규화한 뒤
 * "지급됐다고 기대한 돈"과 "실제로 posted 된 돈"을 어떤 기준으로 확정할지 담당한다.
 * 그래서 실제 계약이 없는 로컬에서도 pending→posted, 금액 차이, reference 누락을 재현할 수 있다.
 *
 * <p>핵심 경계는 세 가지다.
 * <ul>
 *   <li>pending 외부 행은 금액이 같아도 MATCHED가 아니다.</li>
 *   <li>reference가 없거나 중복이면 금액만으로 PAID_OUT을 확정하지 않는다.</li>
 *   <li>통화가 다르면 환율을 추측하지 않고 별도 예외로 남긴다.</li>
 * </ul>
 */
public final class PayoutReconciliationEngine {

    public record SettlementExpectation(String payoutReference, String currency, long expectedAmount) {
        public SettlementExpectation {
            requireText(payoutReference, "payoutReference");
            requireText(currency, "currency");
            if (expectedAmount < 0) {
                throw new IllegalArgumentException("expectedAmount는 음수일 수 없습니다: " + expectedAmount);
            }
        }
    }

    public record ExternalPayout(String payoutReference, String currency, long actualAmount, boolean posted) {
        public ExternalPayout {
            if (currency == null || currency.isBlank()) {
                throw new IllegalArgumentException("currency가 비어 있습니다");
            }
            if (actualAmount < 0) {
                throw new IllegalArgumentException("actualAmount는 음수일 수 없습니다: " + actualAmount);
            }
        }

        public static ExternalPayout pending(String payoutReference, String currency, long actualAmount) {
            return new ExternalPayout(payoutReference, currency, actualAmount, false);
        }

        public static ExternalPayout posted(String payoutReference, String currency, long actualAmount) {
            return new ExternalPayout(payoutReference, currency, actualAmount, true);
        }
    }

    public record Result(
            PayoutReconciliationStatus status,
            String payoutReference,
            String currency,
            long expectedAmount,
            Long actualAmount,
            long difference,
            String reason) {
    }

    /**
     * 같은 입력이면 같은 순서와 같은 판정을 반환한다. 외부 파일의 행 순서에 결과가 좌우되지 않는다.
     */
    public List<Result> reconcile(List<SettlementExpectation> expectations,
                                  List<ExternalPayout> payouts) {
        Map<String, SettlementExpectation> expectedByReference = new HashMap<>();
        for (SettlementExpectation expectation : expectations) {
            if (expectedByReference.putIfAbsent(expectation.payoutReference(), expectation) != null) {
                throw new IllegalArgumentException(
                        "내부 지급 reference가 중복됩니다: " + expectation.payoutReference());
            }
        }

        Map<String, List<ExternalPayout>> externalByReference = new HashMap<>();
        List<ExternalPayout> withoutReference = new ArrayList<>();
        for (ExternalPayout payout : payouts) {
            if (payout.payoutReference() == null || payout.payoutReference().isBlank()) {
                withoutReference.add(payout);
            } else {
                externalByReference
                        .computeIfAbsent(payout.payoutReference(), ignored -> new ArrayList<>())
                        .add(payout);
            }
        }

        Set<String> references = new HashSet<>(expectedByReference.keySet());
        references.addAll(externalByReference.keySet());
        List<String> sortedReferences = references.stream().sorted().toList();
        List<Result> results = new ArrayList<>();

        for (String reference : sortedReferences) {
            SettlementExpectation expected = expectedByReference.get(reference);
            List<ExternalPayout> actuals = externalByReference.get(reference);

            if (expected == null) {
                for (ExternalPayout actual : actuals) {
                    results.add(new Result(
                            PayoutReconciliationStatus.UNMATCHED_PAYOUT,
                            reference,
                            actual.currency(),
                            0,
                            actual.actualAmount(),
                            actual.actualAmount(),
                            "내부 정산 기대치가 없는 외부 지급"));
                }
                continue;
            }
            if (actuals == null) {
                results.add(new Result(
                        PayoutReconciliationStatus.UNMATCHED_SETTLEMENT,
                        reference,
                        expected.currency(),
                        expected.expectedAmount(),
                        null,
                        -expected.expectedAmount(),
                        "외부 지급 report에 reference가 없음"));
                continue;
            }
            if (actuals.size() > 1) {
                results.add(new Result(
                        PayoutReconciliationStatus.DUPLICATE_PAYOUT_REFERENCE,
                        reference,
                        expected.currency(),
                        expected.expectedAmount(),
                        null,
                        0,
                        "외부 지급 report에 같은 reference가 여러 번 있음"));
                continue;
            }

            ExternalPayout actual = actuals.getFirst();
            if (!actual.posted()) {
                results.add(new Result(
                        PayoutReconciliationStatus.PENDING,
                        reference,
                        actual.currency(),
                        expected.expectedAmount(),
                        actual.actualAmount(),
                        safeDifference(actual.actualAmount(), expected.expectedAmount()),
                        "외부 지급이 아직 posted 상태가 아님"));
            } else if (!expected.currency().equals(actual.currency())) {
                results.add(new Result(
                        PayoutReconciliationStatus.CURRENCY_MISMATCH,
                        reference,
                        actual.currency(),
                        expected.expectedAmount(),
                        actual.actualAmount(),
                        0,
                        "환율·반올림 정책 없이 통화를 비교할 수 없음"));
            } else if (expected.expectedAmount() != actual.actualAmount()) {
                results.add(new Result(
                        PayoutReconciliationStatus.AMOUNT_MISMATCH,
                        reference,
                        actual.currency(),
                        expected.expectedAmount(),
                        actual.actualAmount(),
                        safeDifference(actual.actualAmount(), expected.expectedAmount()),
                        "posted 금액과 내부 지급 기대치가 다름"));
            } else {
                results.add(new Result(
                        PayoutReconciliationStatus.MATCHED,
                        reference,
                        actual.currency(),
                        expected.expectedAmount(),
                        actual.actualAmount(),
                        0,
                        "reference·통화·posted 금액이 모두 일치"));
            }
        }

        withoutReference.sort(Comparator.comparing(ExternalPayout::currency)
                .thenComparingLong(ExternalPayout::actualAmount));
        for (ExternalPayout actual : withoutReference) {
            results.add(new Result(
                    PayoutReconciliationStatus.UNMATCHED_PAYOUT,
                    null,
                    actual.currency(),
                    0,
                    actual.actualAmount(),
                    actual.actualAmount(),
                    "외부 지급 reference가 없어 내부 정산과 연결할 수 없음"));
        }
        return List.copyOf(results);
    }

    private static long safeDifference(long actual, long expected) {
        return Math.subtractExact(actual, expected);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + "가 비어 있습니다");
        }
    }
}
