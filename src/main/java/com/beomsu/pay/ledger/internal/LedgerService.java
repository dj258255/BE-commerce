package com.beomsu.pay.ledger.internal;

import com.beomsu.pay.dispute.DisputeLostEvent;
import com.beomsu.pay.payment.PaymentCanceledEvent;
import com.beomsu.pay.payment.PaymentConfirmedEvent;
import com.beomsu.pay.settlement.SettlementPaidOutEvent;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 원장 서비스 — 결제 사건을 복식부기 분개로 기록한다.
 *
 * <p>결제 승인: PG 미수금(차변) ↔ 매출(대변). 취소: 그 역분개. 원거래를 지우지 않고 반대 분개를
 * 추가해 이력을 보존한다. (txType, sourceType, sourceId) 유니크로 같은 이벤트의 중복 분개를 막는다.
 */
@Service
@RequiredArgsConstructor
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);
    private static final String SOURCE_PAYMENT = "PAYMENT";
    private static final String SOURCE_DISPUTE = "DISPUTE";
    private static final String SOURCE_SETTLEMENT = "SETTLEMENT";

    private final LedgerTransactionRepository repository;
    private final LedgerEntryRepository entryRepository;

    /** 최근 원장 트랜잭션 조회(감사용) — 분개 목록·균형 여부 포함. */
    @Transactional(readOnly = true)
    public List<LedgerView> recentTransactions() {
        return repository.findTop50ByOrderByIdDesc().stream()
                .map(LedgerView::from)
                .toList();
    }

    /**
     * 계정별 잔액 — {@code ledger/package-info} 가 "잔액은 엔트리의 합으로 파생된다"고 선언한 것을
     * <b>실제로 쓰는 첫 조회</b>다. 그전까지 SUM 이 <b>선택</b>으로만 적혀 있었고 그 대가를 한 번도
     * 치른 적이 없었다(ADR-025).
     *
     * <p>값은 원장을 유일한 진실로 두고 매번 다시 센다. 그래서 원장과 갈라질 수 없고, 그 대신 비용이
     * 원장 행 수에 비례한다. 그 비용의 실측이 ADR-025 가 스냅샷 도입 조건을 정하는 근거다.
     */
    @Transactional(readOnly = true)
    public List<LedgerBalance> balances() {
        return entryRepository.sumByAccount().stream()
                .map(row -> new LedgerBalance(row.getAccount(), row.getBalance()))
                .toList();
    }

    /** 한 계정의 잔액. 인덱스가 있으면 그 계정의 분개만 훑는다. */
    @Transactional(readOnly = true)
    public LedgerBalance balance(AccountType account) {
        return new LedgerBalance(account, entryRepository.sumByAccount(account));
    }

    @Transactional
    public void recordPaymentConfirmed(PaymentConfirmedEvent event) {
        if (alreadyRecorded("PAYMENT_APPROVED", event.paymentId())) {
            return; // 멱등: 이미 분개함
        }
        long amount = event.amount();
        LedgerTransaction tx = LedgerTransaction.of(
                "PAYMENT_APPROVED", SOURCE_PAYMENT, event.paymentId(),
                "결제 승인 " + event.orderNo(),
                List.of(
                        LedgerEntry.debit(AccountType.PG_RECEIVABLE, amount),
                        LedgerEntry.credit(AccountType.SALES, amount)
                ));
        repository.save(tx);
    }

    @Transactional
    public void recordPaymentCanceled(PaymentCanceledEvent event) {
        // 취소 건마다 다른 순번을 쓴다. 원결제 ID만 보면 두 번째 부분취소가 조용히 사라진다.
        long cancelAmount = event.cancelAmount();
        if (alreadyRecorded("PAYMENT_CANCELED", event.paymentId(), event.cancelSeq())) {
            return;
        }
        LedgerTransaction tx = LedgerTransaction.of(
                "PAYMENT_CANCELED", SOURCE_PAYMENT, event.paymentId(), event.cancelSeq(),
                "결제 취소 " + event.orderNo() + " #" + event.cancelSeq(),
                List.of(
                        LedgerEntry.debit(AccountType.SALES, cancelAmount),          // 역분개
                        LedgerEntry.credit(AccountType.PG_RECEIVABLE, cancelAmount)
                ));
        repository.save(tx);
    }

    /**
     * 분쟁 패소 → 원매출 역분개. 취소({@link #recordPaymentCanceled})와 같은 방향으로 되돌린다:
     * 매출(차변) ↔ PG 미수금(대변). 원거래를 지우지 않고 반대 분개를 추가해 이력을 보존한다.
     * (txType="DISPUTE_LOST", sourceType="DISPUTE", sourceId=disputeId) 유니크로 <b>멱등</b> —
     * 같은 패소 이벤트가 재전달돼도 역분개는 한 번만.
     */
    @Transactional
    public void recordDisputeLost(DisputeLostEvent event) {
        if (repository.existsByTxTypeAndSourceTypeAndSourceIdAndSourceSeq(
                "DISPUTE_LOST", SOURCE_DISPUTE, event.disputeId(), 0)) {
            return; // 멱등: 이미 역분개함
        }
        long amount = event.amount();
        LedgerTransaction tx = LedgerTransaction.of(
                "DISPUTE_LOST", SOURCE_DISPUTE, event.disputeId(),
                "분쟁 패소 역분개 " + event.orderNo(),
                List.of(
                        LedgerEntry.debit(AccountType.SALES, amount),          // 역분개
                        LedgerEntry.credit(AccountType.PG_RECEIVABLE, amount)
                ));
        try {
            repository.save(tx);
        } catch (DataIntegrityViolationException e) {
            // 동시 이벤트 2건이 existsBy를 모두 통과한 레이스 — (txType,sourceType,sourceId) 유니크가
            // 두 번째 insert를 막는다. 이미 역분개됐으므로 멱등 흡수한다(예외를 삼켜 리스너 실패·재시도 방지).
            log.info("분쟁 패소 역분개 중복(멱등 흡수) disputeId={}", event.disputeId());
        }
    }

    /**
     * PG 지급 확정 → <b>미수금 회수</b> 분개. (차)보통예금 + 지급수수료 / (대)PG미수금.
     *
     * <p>PG는 총액에서 수수료와 그 부가세를 뗀 금액을 보낸다. 그래서 회수되는 미수금(gross)은
     * 실제 입금액(net)과 우리가 부담한 수수료(feeTotal)의 합으로 갈라 적는다. 이 셋이 맞아야
     * 차대가 균형을 이루고, <b>미수금 = 승인합 − 취소합 − 입금합</b>이라는 검증식이 선다.
     *
     * <p>멱등: (SETTLEMENT_PAID_OUT, SETTLEMENT, settlementId) 유니크.
     */
    @Transactional
    public void recordSettlementPaidOut(SettlementPaidOutEvent event) {
        if (repository.existsByTxTypeAndSourceTypeAndSourceIdAndSourceSeq(
                "SETTLEMENT_PAID_OUT", SOURCE_SETTLEMENT, event.settlementId(), 0)) {
            return; // 멱등: 이미 회수 분개함
        }
        LedgerTransaction tx = LedgerTransaction.of(
                "SETTLEMENT_PAID_OUT", SOURCE_SETTLEMENT, event.settlementId(),
                "정산 지급 확정 · PG 미수금 회수",
                List.of(
                        LedgerEntry.debit(AccountType.CASH, event.netAmount()),
                        LedgerEntry.debit(AccountType.PG_FEE, event.feeTotal()),
                        LedgerEntry.credit(AccountType.PG_RECEIVABLE, event.grossAmount())
                ));
        try {
            repository.save(tx);
        } catch (DataIntegrityViolationException e) {
            log.info("정산 지급 분개 중복(멱등 흡수) settlementId={}", event.settlementId());
        }
    }

    private boolean alreadyRecorded(String txType, long paymentId) {
        return alreadyRecorded(txType, paymentId, 0);
    }

    private boolean alreadyRecorded(String txType, long paymentId, int sourceSeq) {
        return repository.existsByTxTypeAndSourceTypeAndSourceIdAndSourceSeq(
                txType, SOURCE_PAYMENT, paymentId, sourceSeq);
    }
}
