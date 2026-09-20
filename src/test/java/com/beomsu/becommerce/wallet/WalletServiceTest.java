package com.beomsu.becommerce.wallet;

import com.beomsu.becommerce.wallet.internal.WalletTransactionType;
import com.beomsu.becommerce.wallet.internal.WalletTransactionRepository;
import com.beomsu.becommerce.wallet.internal.WalletTransaction;
import com.beomsu.becommerce.wallet.internal.WalletException;
import com.beomsu.becommerce.wallet.internal.WalletAccountRepository;
import com.beomsu.becommerce.wallet.internal.WalletAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WalletServiceTest {

    private WalletAccountRepository accountRepository;
    private WalletTransactionRepository transactionRepository;
    private WalletService service;

    @BeforeEach
    void setUp() {
        accountRepository = mock(WalletAccountRepository.class);
        transactionRepository = mock(WalletTransactionRepository.class);
        service = new WalletService(accountRepository, transactionRepository,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    @Test
    @DisplayName("charge: 잔액을 늘리고 CHARGE 이력(balanceAfter 포함)을 남긴다")
    void chargeAddsAndRecords() {
        WalletAccount account = WalletAccount.of(1L);
        when(accountRepository.findById(1L)).thenReturn(Optional.of(account));

        long balance = service.charge(1L, 500_000);

        assertThat(balance).isEqualTo(500_000);
        assertThat(account.getBalance()).isEqualTo(500_000);
        verify(accountRepository).saveAndFlush(account);
        ArgumentCaptor<WalletTransaction> captor = ArgumentCaptor.forClass(WalletTransaction.class);
        verify(transactionRepository).save(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo(WalletTransactionType.CHARGE);
        assertThat(captor.getValue().getAmount()).isEqualTo(500_000);
        assertThat(captor.getValue().getBalanceAfter()).isEqualTo(500_000);
    }

    @Test
    @DisplayName("charge: 계정이 없으면 잔액 0에서 새로 만들어 충전한다")
    void chargeCreatesAccountWhenAbsent() {
        when(accountRepository.findById(9L)).thenReturn(Optional.empty());

        long balance = service.charge(9L, 100_000);

        assertThat(balance).isEqualTo(100_000);
    }

    @Test
    @DisplayName("use: 잔액을 차감하고 USE 이력을 남긴다")
    void useDeductsAndRecords() {
        WalletAccount account = WalletAccount.of(1L);
        account.charge(10_000);
        when(accountRepository.findById(1L)).thenReturn(Optional.of(account));

        long balance = service.use(1L, 6_000, "ord-1");

        assertThat(balance).isEqualTo(4_000);
        verify(accountRepository).saveAndFlush(account);
        ArgumentCaptor<WalletTransaction> captor = ArgumentCaptor.forClass(WalletTransaction.class);
        verify(transactionRepository).save(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo(WalletTransactionType.USE);
        assertThat(captor.getValue().getBalanceAfter()).isEqualTo(4_000);
    }

    @Test
    @DisplayName("charge: 한도 초과면 LIMIT_EXCEEDED 전파, 이력 저장 안 함")
    void chargeOverLimitPropagates() {
        WalletAccount account = WalletAccount.of(1L);
        account.charge(1_800_000);
        when(accountRepository.findById(1L)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> service.charge(1L, 300_000))
                .isInstanceOf(WalletException.class)
                .satisfies(e -> assertThat(((WalletException) e).code()).isEqualTo("LIMIT_EXCEEDED"));
        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("use: 잔액 부족이면 INSUFFICIENT_BALANCE 전파, 이력 저장 안 함")
    void useInsufficientPropagates() {
        WalletAccount account = WalletAccount.of(1L);
        account.charge(2_000);
        when(accountRepository.findById(1L)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> service.use(1L, 6_000, "ord-1"))
                .isInstanceOf(WalletException.class)
                .satisfies(e -> assertThat(((WalletException) e).code()).isEqualTo("INSUFFICIENT_BALANCE"));
        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("use: 활성 예약(USE−RESTORE−REFUND>0)이 남아 있으면 재차감하지 않는다(멱등)")
    void useSkipsWhenActiveReservationExists() {
        WalletAccount account = WalletAccount.of(1L);
        account.charge(10_000);
        // 활성 예약 6,000 존재(USE 6,000, RESTORE·REFUND 0) → skip
        when(transactionRepository.sumAmountByOrderNoAndType("ord-1", WalletTransactionType.USE)).thenReturn(6_000L);
        when(accountRepository.findById(1L)).thenReturn(Optional.of(account));

        long balance = service.use(1L, 6_000, "ord-1");

        assertThat(balance).isEqualTo(10_000); // 재차감 없음 — 잔액 그대로
        verify(accountRepository, never()).saveAndFlush(any());
        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("use: 예약이 RESTORE로 해제됐으면(순예약 0) 재시도 시 다시 차감한다(거절→재시도 이중무료 방지)")
    void useReappliesAfterRestore() {
        WalletAccount account = WalletAccount.of(1L);
        account.charge(10_000);
        // USE 6,000이 있었지만 RESTORE 6,000으로 해제됨 → 순예약 0 → 재차감해야 함
        when(transactionRepository.sumAmountByOrderNoAndType("ord-1", WalletTransactionType.USE)).thenReturn(6_000L);
        when(transactionRepository.sumAmountByOrderNoAndType("ord-1", WalletTransactionType.RESTORE)).thenReturn(6_000L);
        when(accountRepository.findById(1L)).thenReturn(Optional.of(account));

        long balance = service.use(1L, 6_000, "ord-1");

        assertThat(balance).isEqualTo(4_000); // 다시 차감됨
        verify(transactionRepository).save(any());
    }

    @Test
    @DisplayName("restore: 같은 주문의 RESTORE 이력이 이미 있으면 이중해제하지 않는다(멱등 — 복구 재실행 안전)")
    void restoreIsIdempotentPerOrder() {
        WalletAccount account = WalletAccount.of(1L);
        account.charge(6_000);
        when(transactionRepository.existsByOrderNoAndType("ord-1", WalletTransactionType.RESTORE))
                .thenReturn(true); // 이미 해제됨
        when(accountRepository.findById(1L)).thenReturn(Optional.of(account));

        long balance = service.restore(1L, 4_000, "ord-1");

        assertThat(balance).isEqualTo(6_000); // 이중해제 없음
        verify(accountRepository, never()).saveAndFlush(any());
        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("refund: 취소 환불은 비멱등 — 부분취소가 여러 번 와도 매번 반영한다")
    void refundIsNotIdempotent() {
        WalletAccount account = WalletAccount.of(1L);
        account.charge(6_000);
        when(accountRepository.findById(1L)).thenReturn(Optional.of(account));

        long balance = service.refund(1L, 4_000, "ord-1"); // REFUND 이력 존재 여부와 무관하게 반영

        assertThat(balance).isEqualTo(10_000);
        verify(transactionRepository).save(any());
    }

    @Test
    @DisplayName("refund: 잔액을 되돌리고 REFUND 이력을 남긴다")
    void refundAddsAndRecords() {
        WalletAccount account = WalletAccount.of(1L);
        account.charge(10_000);
        account.use(4_000);
        when(accountRepository.findById(1L)).thenReturn(Optional.of(account));

        long balance = service.refund(1L, 4_000, "ord-1");

        assertThat(balance).isEqualTo(10_000);
        ArgumentCaptor<WalletTransaction> captor = ArgumentCaptor.forClass(WalletTransaction.class);
        verify(transactionRepository).save(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo(WalletTransactionType.REFUND);
    }

    @Test
    @DisplayName("balance: 계정이 없으면 0")
    void balanceZeroWhenNoAccount() {
        when(accountRepository.findById(99L)).thenReturn(Optional.empty());

        assertThat(service.balance(99L)).isZero();
    }
}
