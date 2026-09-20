package com.beomsu.becommerce.ledger.internal;

import com.beomsu.becommerce.ledger.internal.LedgerView;
import com.beomsu.becommerce.ledger.internal.LedgerTransactionRepository;
import com.beomsu.becommerce.ledger.internal.LedgerTransaction;
import com.beomsu.becommerce.ledger.internal.LedgerService;
import com.beomsu.becommerce.ledger.internal.LedgerEntry;
import com.beomsu.becommerce.ledger.internal.EntryDirection;
import com.beomsu.becommerce.ledger.internal.AccountType;
import com.beomsu.becommerce.dispute.DisputeLostEvent;
import com.beomsu.becommerce.payment.PaymentCanceledEvent;
import com.beomsu.becommerce.settlement.SettlementPaidOutEvent;
import com.beomsu.becommerce.payment.PaymentConfirmedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LedgerServiceTest {

    private LedgerTransactionRepository repository;
    private LedgerEntryRepository entryRepository;
    private LedgerService service;

    private final PaymentConfirmedEvent event =
            new PaymentConfirmedEvent("order-1", 100L, 10_000, Instant.now());

    @BeforeEach
    void setUp() {
        repository = mock(LedgerTransactionRepository.class);
        entryRepository = mock(LedgerEntryRepository.class);
        service = new LedgerService(repository, entryRepository);
    }

    @Test
    @DisplayName("계정별 잔액: projection 을 그대로 매핑한다 — 부호는 분개 방향 그대로")
    void mapsBalancesFromProjection() {
        LedgerEntryRepository.AccountBalanceRow receivable = balanceRow(AccountType.PG_RECEIVABLE, 100_000);
        LedgerEntryRepository.AccountBalanceRow sales = balanceRow(AccountType.SALES, -100_000);
        when(entryRepository.sumByAccount()).thenReturn(List.of(receivable, sales));

        List<LedgerBalance> balances = service.balances();

        assertThat(balances)
                .containsExactlyInAnyOrder(
                        new LedgerBalance(AccountType.PG_RECEIVABLE, 100_000),
                        new LedgerBalance(AccountType.SALES, -100_000));
    }

    @Test
    @DisplayName("한 계정 잔액: 리포지토리 합계를 그대로 반환한다")
    void returnsSingleAccountBalance() {
        when(entryRepository.sumByAccount(AccountType.CASH)).thenReturn(97_030L);

        assertThat(service.balance(AccountType.CASH))
                .isEqualTo(new LedgerBalance(AccountType.CASH, 97_030));
    }

    private static LedgerEntryRepository.AccountBalanceRow balanceRow(AccountType account, long balance) {
        return new LedgerEntryRepository.AccountBalanceRow() {
            @Override
            public AccountType getAccount() {
                return account;
            }

            @Override
            public long getBalance() {
                return balance;
            }
        };
    }

    @Test
    @DisplayName("최근 원장 조회: 트랜잭션을 뷰로 매핑하고 균형(balanced) 여부를 계산한다")
    void recentTransactionsMapsToViewWithBalance() {
        when(repository.existsByTxTypeAndSourceTypeAndSourceIdAndSourceSeq(anyString(), anyString(), anyLong(), anyInt()))
                .thenReturn(false);
        ArgumentCaptor<LedgerTransaction> captor = ArgumentCaptor.forClass(LedgerTransaction.class);
        service.recordPaymentConfirmed(event);
        verify(repository).save(captor.capture());
        when(repository.findTop50ByOrderByIdDesc()).thenReturn(List.of(captor.getValue()));

        List<LedgerView> views = service.recentTransactions();

        assertThat(views).hasSize(1);
        assertThat(views.get(0).balanced()).isTrue();        // 차변=대변
        assertThat(views.get(0).entries()).hasSize(2);
        assertThat(views.get(0).txType()).isEqualTo("PAYMENT_APPROVED");
    }

    @Test
    @DisplayName("결제 승인: PG미수금(차변) ↔ 매출(대변), 균형 잡힌 분개 저장")
    void recordsBalancedEntriesOnConfirm() {
        when(repository.existsByTxTypeAndSourceTypeAndSourceIdAndSourceSeq(anyString(), anyString(), anyLong(), anyInt()))
                .thenReturn(false);

        service.recordPaymentConfirmed(event);

        ArgumentCaptor<LedgerTransaction> captor = ArgumentCaptor.forClass(LedgerTransaction.class);
        verify(repository).save(captor.capture());
        LedgerTransaction tx = captor.getValue();
        assertThat(tx.imbalance()).isZero();                 // 차변=대변
        assertThat(tx.getEntries()).hasSize(2);
    }

    @Test
    @DisplayName("같은 결제 이벤트가 두 번 와도 분개는 한 번만 (멱등)")
    void idempotentOnDuplicateEvent() {
        when(repository.existsByTxTypeAndSourceTypeAndSourceIdAndSourceSeq("PAYMENT_APPROVED", "PAYMENT", 100L, 0))
                .thenReturn(true);

        service.recordPaymentConfirmed(event);

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("분쟁 패소: 매출(차변) ↔ PG미수금(대변) 역분개, 균형 잡힌 분개 저장")
    void recordsBalancedReversalOnDisputeLost() {
        when(repository.existsByTxTypeAndSourceTypeAndSourceIdAndSourceSeq(anyString(), anyString(), anyLong(), anyInt()))
                .thenReturn(false);

        service.recordDisputeLost(new DisputeLostEvent("order-1", 100L, 10_000, 42L));

        ArgumentCaptor<LedgerTransaction> captor = ArgumentCaptor.forClass(LedgerTransaction.class);
        verify(repository).save(captor.capture());
        LedgerTransaction tx = captor.getValue();
        assertThat(tx.imbalance()).isZero();                 // 차변=대변
        assertThat(tx.getEntries()).hasSize(2);
        assertThat(tx.getTxType()).isEqualTo("DISPUTE_LOST");
        assertThat(tx.getSourceType()).isEqualTo("DISPUTE");
        assertThat(tx.getSourceId()).isEqualTo(42L);
        // 역분개 방향: SALES 차변, PG_RECEIVABLE 대변(결제취소와 동일)
        LedgerEntry sales = tx.getEntries().stream()
                .filter(e -> e.getAccount() == AccountType.SALES).findFirst().orElseThrow();
        assertThat(sales.getDirection()).isEqualTo(EntryDirection.DEBIT);
    }

    @Test
    @DisplayName("정산 지급 확정: 보통예금+수수료(차변) ↔ PG미수금(대변) — 미수금이 이때 회수된다")
    void recordsReceivableCollectionOnPayout() {
        // 이 분개가 없으면 PG 미수금은 승인으로 늘기만 하고 줄지 않아, 계정 잔액이 실제 채권과
        // 영원히 어긋난다. gross 100,000 = net 97,030 + 수수료·부가세 2,970.
        when(repository.existsByTxTypeAndSourceTypeAndSourceIdAndSourceSeq(
                "SETTLEMENT_PAID_OUT", "SETTLEMENT", 7L, 0)).thenReturn(false);

        service.recordSettlementPaidOut(new SettlementPaidOutEvent(7L, 100_000, 2_970, 97_030));

        ArgumentCaptor<LedgerTransaction> captor = ArgumentCaptor.forClass(LedgerTransaction.class);
        verify(repository).save(captor.capture());
        LedgerTransaction tx = captor.getValue();
        // 차대가 맞는다는 것은 LedgerTransaction.of가 이미 강제한다(불균형이면 생성 자체가 실패).
        // 여기서 확인할 것은 회수 금액이 총액이라는 점이다 — 실입금액만 대변에 적으면 수수료만큼
        // 미수금이 영영 남는다.
        assertThat(tx.getEntries())
                .filteredOn(e -> e.getAccount() == AccountType.PG_RECEIVABLE)
                .singleElement()
                .satisfies(e -> assertThat(e.getAmount()).isEqualTo(100_000));
    }

    @Test
    @DisplayName("같은 결제의 두 번째 부분취소도 역분개된다 — 원결제 ID만 보면 조용히 사라진다")
    void secondPartialCancelIsAlsoRecorded() {
        // 1차 취소(순번 1)는 이미 분개됐고, 2차 취소(순번 2)가 들어온다.
        when(repository.existsByTxTypeAndSourceTypeAndSourceIdAndSourceSeq(
                "PAYMENT_CANCELED", "PAYMENT", 100L, 1)).thenReturn(true);
        when(repository.existsByTxTypeAndSourceTypeAndSourceIdAndSourceSeq(
                "PAYMENT_CANCELED", "PAYMENT", 100L, 2)).thenReturn(false);

        service.recordPaymentCanceled(
                new PaymentCanceledEvent("order-1", 100L, 2, 5_000, 10_000, false, java.time.Instant.now()));

        ArgumentCaptor<LedgerTransaction> captor = ArgumentCaptor.forClass(LedgerTransaction.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getSourceSeq())
                .as("취소 순번이 중복 판정 키에 들어가야 2차 취소가 원장에 남는다")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("같은 취소가 두 번 배달되면 역분개는 한 번만 (같은 순번 = 같은 취소)")
    void sameCancelDeliveredTwiceIsRecordedOnce() {
        when(repository.existsByTxTypeAndSourceTypeAndSourceIdAndSourceSeq(
                "PAYMENT_CANCELED", "PAYMENT", 100L, 1)).thenReturn(true);

        service.recordPaymentCanceled(
                new PaymentCanceledEvent("order-1", 100L, 1, 5_000, 15_000, false, java.time.Instant.now()));

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("같은 분쟁 패소 이벤트가 두 번 와도 역분개는 한 번만 (멱등, disputeId 기준)")
    void idempotentOnDuplicateDisputeLost() {
        when(repository.existsByTxTypeAndSourceTypeAndSourceIdAndSourceSeq("DISPUTE_LOST", "DISPUTE", 42L, 0))
                .thenReturn(true);

        service.recordDisputeLost(new DisputeLostEvent("order-1", 100L, 10_000, 42L));

        verify(repository, never()).save(any());
    }
}
