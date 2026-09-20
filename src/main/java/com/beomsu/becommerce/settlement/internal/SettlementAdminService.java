package com.beomsu.becommerce.settlement.internal;

import com.beomsu.becommerce.settlement.SettlementPaidOutEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 정산 운영 어드민 — 정산 집계를 조회하고, 가맹점 지급을 확정하며, 정산 배치를 수동 실행한다.
 *
 * <p>그동안 {@code settle()} 배치는 테스트에서만 호출되고 정산을 볼 어드민도 없었다. 이 어드민이
 * <b>조회 → 지급 확정</b> 루프를 닫고, 데모·수동 운영을 위한 {@link #runSettlement(LocalDate)}로
 * 배치를 즉시 돌릴 수 있게 한다(주기 실행은 {@link SettlementScheduler}).
 */
@Service
@RequiredArgsConstructor
public class SettlementAdminService {

    private final SettlementRepository repository;
    private final SettlementItemRepository itemRepository;
    private final SettlementService settlementService;
    private final ApplicationEventPublisher events;

    /** 정산 집계 페이지(뷰 record로 노출). */
    @Transactional(readOnly = true)
    public Page<SettlementView> list(Pageable pageable) {
        return repository.findAll(pageable).map(SettlementView::from);
    }

    /**
     * 정산 한 건이 <b>언제 번 돈</b>으로 이뤄졌는지 낸다.
     *
     * <p>읽기만 한다. 지급 금액도 과거 집계도 바뀌지 않는다. 배치가 그 날짜 이하를 쓸어 담기
     * 때문에 정산일은 쓸어 담은 날이고, 그 안에 며칠 전 거래가 섞인다. 판매자가 "오늘 왜
     * 두 배인가"를 물으면 답할 자리가 여기다(ADR-023).
     */
    @Transactional(readOnly = true)
    public SettlementCompositionView composition(long settlementId) {
        Settlement settlement = repository.findById(settlementId)
                .orElseThrow(() -> SettlementException.notFound(settlementId));
        LocalDate settlementDate = settlement.getSettlementDate();

        // 확정일별로 묶는다. 취소로 금액이 깎인 항목도 지금 금액 그대로 센다 —
        // 이 뷰가 답하는 것은 "무엇이 지급됐나"가 아니라 "언제 번 돈인가"다.
        Map<LocalDate, List<SettlementItem>> byDate = itemRepository.findBySettlementId(settlementId)
                .stream()
                .collect(Collectors.groupingBy(SettlementItem::getConfirmedDate, TreeMap::new, Collectors.toList()));

        List<SettlementCompositionView.Line> lines = byDate.entrySet().stream()
                .map(e -> new SettlementCompositionView.Line(
                        e.getKey(),
                        e.getValue().size(),
                        e.getValue().stream().mapToLong(SettlementItem::getAmount).sum(),
                        ChronoUnit.DAYS.between(e.getKey(), settlementDate)))
                .toList();

        long total = lines.stream().mapToLong(SettlementCompositionView.Line::amount).sum();
        long sameDate = lines.stream()
                .filter(l -> l.confirmedDate().equals(settlementDate))
                .mapToLong(SettlementCompositionView.Line::amount).sum();
        boolean crossesMonth = lines.stream()
                .anyMatch(l -> !YearMonth.from(l.confirmedDate()).equals(YearMonth.from(settlementDate)));

        return new SettlementCompositionView(settlementDate, total, sameDate,
                total - sameDate, crossesMonth, lines);
    }

    /**
     * 정산 1건의 지급을 확정한다(CREATED → PAID_OUT).
     *
     * <p>{@link Settlement#markPaidOut()} 후 {@code saveAndFlush}로 <b>명시 영속</b>한다. dirty-check
     * 자동 flush는 readOnly 조회로 세션 FlushMode가 MANUAL이 되거나 detached 엔티티인 경우 신뢰할 수 없어
     * (pay-26 사건 교훈) 상태 확정을 강제한다.
     * markPaidOut은 멱등이라 이미 PAID_OUT이면 시각을 덮어쓰지 않는다.
     */
    @Transactional
    public SettlementView confirmPayout(long settlementId) {
        Settlement settlement = repository.findById(settlementId)
                .orElseThrow(() -> SettlementException.notFound(settlementId));
        boolean transitioned = settlement.markPaidOut();
        repository.saveAndFlush(settlement);
        if (transitioned) {
            // 원장이 PG 미수금을 회수 처리한다. 이 사건이 없으면 미수금이 단조 증가만 한다.
            events.publishEvent(new SettlementPaidOutEvent(
                    settlement.getId(), settlement.getGrossAmount(),
                    settlement.getFeeAmount() + settlement.getFeeVatAmount(),
                    settlement.getNetAmount()));
        }
        return SettlementView.from(settlement);
    }

    /**
     * 정산 배치를 수동 실행한다(데모·수동 운영용) — {@link SettlementService#settle(LocalDate)}에 위임한다.
     *
     * @return 생성된 정산, 재실행(그 날짜 정산 존재)이거나 대상 CONFIRMED 항목이 없으면 null
     */
    @Transactional
    public Settlement runSettlement(LocalDate date) {
        return settlementService.settle(date);
    }
}
