package com.beomsu.becommerce.point;

import com.beomsu.becommerce.point.internal.PointHistoryType;
import com.beomsu.becommerce.point.internal.PointHistoryRepository;
import com.beomsu.becommerce.point.internal.PointHistory;
import com.beomsu.becommerce.point.internal.PointException;
import com.beomsu.becommerce.point.internal.PointAccountRepository;
import com.beomsu.becommerce.point.internal.PointAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PointServiceTest {

    private PointAccountRepository accountRepository;
    private PointHistoryRepository historyRepository;
    private PointService service;

    @BeforeEach
    void setUp() {
        accountRepository = mock(PointAccountRepository.class);
        historyRepository = mock(PointHistoryRepository.class);
        service = new PointService(accountRepository, historyRepository);
    }

    @Test
    @DisplayName("use: 계정 잔액을 차감하고 USE 이력을 남긴다")
    void useDeductsAndRecords() {
        PointAccount account = PointAccount.of(1L, 10_000);
        when(historyRepository.existsByOrderNoAndType("order-1", PointHistoryType.USE)).thenReturn(false);
        when(accountRepository.findById(1L)).thenReturn(Optional.of(account));

        service.use(1L, 6_000, "order-1");

        assertThat(account.getBalance()).isEqualTo(4_000);
        verify(accountRepository).saveAndFlush(account);
        ArgumentCaptor<PointHistory> captor = ArgumentCaptor.forClass(PointHistory.class);
        verify(historyRepository).save(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo(PointHistoryType.USE);
        assertThat(captor.getValue().getAmount()).isEqualTo(6_000);
    }

    @Test
    @DisplayName("earn: 원자 증가로 잔액을 늘리고 EARN 이력을 남긴다 (낙관적 락 경로를 타지 않는다)")
    void earnAddsAndRecords() {
        when(historyRepository.existsByOrderNoAndType("order-1", PointHistoryType.EARN)).thenReturn(false);

        service.earn(1L, 200, "order-1");

        // 경합 실측(승인 성공률 39.6%) 후 read-modify-write 대신 DB 원자 증가로 전환 —
        // findById/saveAndFlush를 타면 낙관적 락 충돌이 결제 실패로 전파되는 회귀다.
        verify(accountRepository).increaseBalanceAtomically(1L, 200);
        verify(accountRepository, never()).findById(any());
        verify(accountRepository, never()).saveAndFlush(any());
        ArgumentCaptor<PointHistory> captor = ArgumentCaptor.forClass(PointHistory.class);
        verify(historyRepository).save(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo(PointHistoryType.EARN);
        assertThat(captor.getValue().getAmount()).isEqualTo(200);
    }

    @Test
    @DisplayName("earn: 같은 주문의 EARN 이력이 이미 있으면 이중적립하지 않는다(멱등)")
    void earnIsIdempotentPerOrder() {
        when(historyRepository.existsByOrderNoAndType("order-1", PointHistoryType.EARN)).thenReturn(true);

        service.earn(1L, 200, "order-1");

        verify(accountRepository, never()).increaseBalanceAtomically(anyLong(), anyLong());
        verify(historyRepository, never()).save(any());
    }

    @Test
    @DisplayName("use: 잔액 부족이면 INSUFFICIENT_POINT 예외, 이력 저장 안 함")
    void useInsufficientThrows() {
        PointAccount account = PointAccount.of(1L, 2_000);
        when(historyRepository.existsByOrderNoAndType("order-1", PointHistoryType.USE)).thenReturn(false);
        when(accountRepository.findById(1L)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> service.use(1L, 6_000, "order-1"))
                .isInstanceOf(PointException.class)
                .satisfies(e -> assertThat(((PointException) e).code()).isEqualTo("INSUFFICIENT_POINT"));
        verify(historyRepository, never()).save(any());
    }

    @Test
    @DisplayName("use: 활성 예약(USE−RESTORE−REFUND>0)이 남아 있으면 멱등 skip (이중 차감 없음)")
    void useIsIdempotent() {
        // 활성 예약 6,000 존재 → skip
        when(historyRepository.sumAmountByOrderNoAndType("order-1", PointHistoryType.USE)).thenReturn(6_000L);

        service.use(1L, 6_000, "order-1");

        verify(accountRepository, never()).findById(anyLong());
        verify(accountRepository, never()).saveAndFlush(any());
        verify(historyRepository, never()).save(any());
    }

    @Test
    @DisplayName("use: 예약이 RESTORE로 해제됐으면 재시도 시 다시 차감한다(거절→재시도 이중무료 방지)")
    void useReappliesAfterRestore() {
        PointAccount account = PointAccount.of(1L, 10_000);
        when(historyRepository.sumAmountByOrderNoAndType("order-1", PointHistoryType.USE)).thenReturn(6_000L);
        when(historyRepository.sumAmountByOrderNoAndType("order-1", PointHistoryType.RESTORE)).thenReturn(6_000L);
        when(accountRepository.findById(1L)).thenReturn(Optional.of(account));

        service.use(1L, 6_000, "order-1");

        assertThat(account.getBalance()).isEqualTo(4_000); // 다시 차감됨
        verify(historyRepository).save(any());
    }

    @Test
    @DisplayName("reverseEarn: 취소 시 적립을 회수하되 적립분을 상한으로 캡한다(파밍 방지)")
    void reverseEarnClawsBackCappedAtEarned() {
        PointAccount account = PointAccount.of(1L, 500);
        when(historyRepository.sumAmountByOrderNoAndType("order-1", PointHistoryType.EARN)).thenReturn(300L);
        when(historyRepository.sumAmountByOrderNoAndType("order-1", PointHistoryType.EARN_REVERSAL)).thenReturn(0L);
        when(accountRepository.findById(1L)).thenReturn(Optional.of(account));

        service.reverseEarn(1L, 1_000, "order-1"); // 요청 1,000이지만 적립 300까지만 회수

        assertThat(account.getBalance()).isEqualTo(200); // 500 - 300
        ArgumentCaptor<PointHistory> captor = ArgumentCaptor.forClass(PointHistory.class);
        verify(historyRepository).save(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo(PointHistoryType.EARN_REVERSAL);
        assertThat(captor.getValue().getAmount()).isEqualTo(300);
    }

    @Test
    @DisplayName("restore: 차감분을 되돌리고 RESTORE 이력을 남긴다 (보상)")
    void restoreAddsAndRecords() {
        PointAccount account = PointAccount.of(1L, 4_000);
        when(historyRepository.existsByOrderNoAndType("order-1", PointHistoryType.RESTORE)).thenReturn(false);
        when(accountRepository.findById(1L)).thenReturn(Optional.of(account));

        service.restore(1L, 6_000, "order-1");

        assertThat(account.getBalance()).isEqualTo(10_000);
        verify(accountRepository).saveAndFlush(account);
        ArgumentCaptor<PointHistory> captor = ArgumentCaptor.forClass(PointHistory.class);
        verify(historyRepository).save(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo(PointHistoryType.RESTORE);
    }

    @Test
    @DisplayName("restore: 같은 주문의 RESTORE 이력이 이미 있으면 멱등 skip (이중 복원 없음)")
    void restoreIsIdempotent() {
        when(historyRepository.existsByOrderNoAndType("order-1", PointHistoryType.RESTORE)).thenReturn(true);

        service.restore(1L, 6_000, "order-1");

        verify(accountRepository, never()).saveAndFlush(any());
        verify(historyRepository, never()).save(any());
    }

    @Test
    @DisplayName("balance: 계정이 없으면 0")
    void balanceZeroWhenNoAccount() {
        when(accountRepository.findById(99L)).thenReturn(Optional.empty());

        assertThat(service.balance(99L)).isZero();
    }
}
