package com.beomsu.becommerce.timeline;

import com.beomsu.becommerce.audit.AuditTimelineFacts;
import com.beomsu.becommerce.dispute.DisputeTimelineFacts;
import com.beomsu.becommerce.escrow.EscrowTimelineFacts;
import com.beomsu.becommerce.ledger.LedgerTimelineFacts;
import com.beomsu.becommerce.payment.PaymentTimelineFacts;
import com.beomsu.becommerce.point.PointTimelineFacts;
import com.beomsu.becommerce.reconciliation.ReconTimelineFacts;
import com.beomsu.becommerce.settlement.SettlementTimelineFacts;
import com.beomsu.becommerce.wallet.WalletTimelineFacts;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

import static com.beomsu.becommerce.timeline.TimelineEntry.Source;
import static com.beomsu.becommerce.timeline.TimelineEntry.of;

/**
 * 도메인별 기여자 (ADR-011).
 *
 * <p>한 파일에 모은 이유: 각 기여자가 "사실을 문장으로 옮기는" 몇 줄뿐이라 파일을 8개로 나누면
 * 오히려 읽기 어렵다. 로직이 붙기 시작하면 그때 쪼갠다.
 *
 * <p><b>요약 문장은 코드가 만든다.</b> 금액을 문자열 조립 과정에서 계산하지 않는다 —
 * 계산은 도메인이 이미 끝냈고 여기서는 옮기기만 한다.
 */
@Configuration(proxyBeanMethods = false)
class DomainContributors {

    /** 기여자 하나를 만드는 틀. 실패해도 조립기가 잡아내므로 여기서 방어하지 않는다. */
    private static TimelineContributor contributor(Source source,
                                                   java.util.function.Function<String, List<TimelineEntry>> fn) {
        return new TimelineContributor() {
            @Override public List<TimelineEntry> contribute(String orderNo) { return fn.apply(orderNo); }
            @Override public Source source() { return source; }
        };
    }

    @Bean
    TimelineContributor escrowContributor(EscrowTimelineFacts facts) {
        return contributor(Source.ESCROW, orderNo -> facts.findByOrderNo(orderNo)
                .map(e -> {
                    var entries = new java.util.ArrayList<TimelineEntry>();
                    // 요약이 <사건 시각이 아닌> 날짜를 말한다. at() 만 모으면 이 날짜가 빠진다.
                    entries.add(of(e.heldAt(), Source.ESCROW, "ESCROW_HELD",
                            "에스크로 보류 — 자동해제 예정 %s".formatted(e.autoReleaseAt()), e.amount(),
                            e.autoReleaseAt().atZone(java.time.ZoneId.of("Asia/Seoul")).toLocalDate()));
                    // 해제됐으면 그 시점도 찍는다. 아직이면 보류 한 줄만 — 없는 사건을 만들지 않는다.
                    if (e.resolvedAt() != null) {
                        entries.add(of(e.resolvedAt(), Source.ESCROW, "ESCROW_" + e.status(),
                                "에스크로 %s".formatted(e.status()), e.amount()));
                    }
                    return List.copyOf(entries);
                })
                .orElseGet(List::of));
    }

    @Bean
    TimelineContributor settlementContributor(SettlementTimelineFacts facts) {
        return contributor(Source.SETTLEMENT, orderNo -> facts.findByOrderNo(orderNo)
                .map(s -> List.of(of(
                        // 정산 항목은 생성 시각이 없고 확정일(날짜)만 있다. 날짜를 그날 자정으로 놓는다 —
                        // 정확한 시각을 모르는데 지어내지 않고, 순서만 맞춘다.
                        s.confirmedDate().atStartOfDay(java.time.ZoneOffset.UTC).toInstant(),
                        Source.SETTLEMENT, "SETTLEMENT_" + s.status(),
                        "정산 항목 %s (확정일 %s)".formatted(s.status(), s.confirmedDate()), s.amount())))
                .orElseGet(List::of));
    }

    @Bean
    TimelineContributor pointContributor(PointTimelineFacts facts) {
        return contributor(Source.POINT, orderNo -> facts.findByOrderNo(orderNo).stream()
                .map(p -> of(p.at(), Source.POINT, "POINT_" + p.type(),
                        "포인트 %s".formatted(p.type()), p.amount()))
                .toList());
    }

    @Bean
    TimelineContributor walletContributor(WalletTimelineFacts facts) {
        return contributor(Source.WALLET, orderNo -> facts.findByOrderNo(orderNo).stream()
                .map(w -> of(w.at(), Source.WALLET, "WALLET_" + w.type(),
                        "월렛 %s — 잔액 %,d원".formatted(w.type(), w.balanceAfter()), w.amount()))
                .toList());
    }

    @Bean
    TimelineContributor disputeContributor(DisputeTimelineFacts facts) {
        return contributor(Source.DISPUTE, orderNo -> facts.findByOrderNo(orderNo).stream()
                .<TimelineEntry>mapMulti((d, sink) -> {
                    sink.accept(of(d.openedAt(), Source.DISPUTE, "DISPUTE_OPENED",
                            "분쟁 접수 (%s) — %s".formatted(d.chargebackId(), d.reason()), d.amount()));
                    if (d.resolvedAt() != null) {
                        sink.accept(of(d.resolvedAt(), Source.DISPUTE, "DISPUTE_" + d.status(),
                                "분쟁 %s".formatted(d.status()), d.amount()));
                    }
                })
                .toList());
    }

    @Bean
    TimelineContributor reconContributor(ReconTimelineFacts facts) {
        return contributor(Source.RECONCILIATION, orderNo -> facts.findByOrderNo(orderNo).stream()
                .<TimelineEntry>mapMulti((r, sink) -> {
                    // 요약이 금액을 <둘> 말한다. 문장만 내면 읽는 쪽이 정규식으로 다시 뽑아야 하고,
                    // 문구를 고칠 때 그 파싱이 조용히 깨진다. 그래서 값을 함께 낸다.
                    var reconFigures = java.util.stream.Stream
                            .of(r.internalAmount(), r.externalAmount())
                            .filter(java.util.Objects::nonNull).map(Math::abs).distinct().toList();
                    sink.accept(new TimelineEntry(r.reconciledAt(), Source.RECONCILIATION,
                            "RECON_" + r.result(),
                            "대사 %s (거래일 %s) — 내부 %s / 외부 %s".formatted(
                                    r.result(), r.tradeDate(),
                                    r.internalAmount() == null ? "없음" : "%,d".formatted(r.internalAmount()),
                                    r.externalAmount() == null ? "없음" : "%,d".formatted(r.externalAmount())),
                            r.internalAmount(), reconFigures,
                            r.tradeDate() == null ? java.util.List.of() : java.util.List.of(r.tradeDate())));
                    if (r.resolvedAt() != null) {
                        sink.accept(of(r.resolvedAt(), Source.RECONCILIATION, "RECON_RESOLVED",
                                "대사 수기 확정 — %s (%s)".formatted(r.resolveCause(), r.resolvedBy())));
                    }
                })
                .toList());
    }

    /**
     * 원장은 주문번호로 못 찾는다(트레이드오프 5). 결제 id를 먼저 해석한 뒤 조회한다.
     * 결제가 없으면(전액 포인트) 빈 목록이고, 그건 <b>정상</b>이다.
     */
    @Bean
    TimelineContributor ledgerContributor(LedgerTimelineFacts ledger, PaymentTimelineFacts payment) {
        return contributor(Source.LEDGER, orderNo -> payment.resolvePaymentId(orderNo)
                .map(paymentId -> ledger.findByPaymentId(paymentId).stream()
                        .map(l -> of(l.at(), Source.LEDGER, "LEDGER_" + l.txType(),
                                "원장 분개 %s — %s".formatted(l.txType(), l.description()), l.amount()))
                        .toList())
                .orElseGet(List::of));
    }

    /**
     * 감사로그는 세 번째 키 형태를 쓴다 — {@code (targetType, targetId)}(ADR-011 트레이드오프 9).
     * 그래서 <b>3단계 해석</b>이 필요하다: orderNo → 결제 id·대사 결과 id → 감사 대상.
     *
     * <p>현재 감사 기록을 남기는 유일한 경로가 대사 수기 확정이므로 실질적으로 그것만 잡히지만,
     * 대상 목록 방식이라 나중에 강제취소·PII 열람이 감사를 남기기 시작하면 그대로 따라온다.
     */
    @Bean
    TimelineContributor auditContributor(AuditTimelineFacts audit,
                                         PaymentTimelineFacts payment,
                                         ReconTimelineFacts recon) {
        return contributor(Source.AUDIT, orderNo -> {
            var targets = new java.util.ArrayList<AuditTimelineFacts.Target>();
            targets.add(new AuditTimelineFacts.Target("ORDER", orderNo));
            payment.resolvePaymentId(orderNo)
                    .ifPresent(id -> targets.add(new AuditTimelineFacts.Target("PAYMENT", String.valueOf(id))));
            recon.findByOrderNo(orderNo).forEach(r ->
                    targets.add(new AuditTimelineFacts.Target("RECONCILIATION_RESULT", String.valueOf(r.id()))));

            return audit.findByTargets(targets).stream()
                    .map(a -> of(a.at(), Source.AUDIT, "AUDIT_" + a.action(),
                            "%s 님이 %s — %s".formatted(a.actor(), a.action(),
                                    a.detail() == null ? a.targetType() : a.detail())))
                    .toList();
        });
    }
}
