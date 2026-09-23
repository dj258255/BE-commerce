package com.beomsu.becommerce.wallet;

import com.beomsu.becommerce.wallet.internal.WalletView;
import com.beomsu.becommerce.wallet.internal.WalletTransactionType;
import com.beomsu.becommerce.wallet.internal.WalletTransactionRepository;
import com.beomsu.becommerce.wallet.internal.WalletTransaction;
import com.beomsu.becommerce.wallet.internal.WalletException;
import com.beomsu.becommerce.wallet.internal.WalletAccountRepository;
import com.beomsu.becommerce.wallet.internal.WalletAccount;
import com.beomsu.becommerce.order.internal.Order;
import jakarta.persistence.OptimisticLockException;
import lombok.RequiredArgsConstructor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 월렛 애플리케이션 서비스 — wallet 모듈의 공개 진입점.
 *
 * <p>충전({@link #charge})·사용({@link #use})·복원({@link #restore})·환불({@link #refund})은 각각
 * {@link WalletAccount}의 잔액 변경 + {@link WalletTransaction} 이력 append를 트랜잭션으로 수행한다.
 *
 * <p><b>결제수단 계약(point와 동일)</b>: 예약 차감은 USE, 사가 보상(승인 실패·재고 부족)의 예약 해제는
 * <b>RESTORE</b>(주문 단위 멱등 — 복구가 settle을 재실행해도 1회), 취소 환불은 <b>REFUND</b>(비멱등 —
 * 한 주문에 부분취소가 여러 번 올 수 있음)로 <b>구분</b>한다. USE 멱등은 "이미 차감했는가"가 아니라
 * <b>활성 예약(USE−RESTORE−REFUND)이 남아 있는가</b>로 판정한다 — 승인 실패로 예약이 RESTORE된 뒤
 * 같은 주문을 재시도하면 다시 차감돼야 하기 때문이다(거절→재시도 이중무료 방지).
 *
 * <p><b>동시성</b>: 낙관적 락(@Version) 충돌 시 최대 {@value #MAX_RETRY}회 재시도한다. 같은 주문의 동시
 * 차감은 상위 {@code Order.startPayment}(@Version)가 직렬화하므로, 멱등 판정(check-then-mutate)이
 * 원자적이지 않아도 이중반영은 일어나지 않는다(point와 동일한 근거). 그래서 (order_no,type) 유니크
 * 인덱스는 두지 않는다 — 재시도로 USE가 2건, 부분취소로 REFUND가 여러 건 쌓일 수 있어야 한다.
 */
@Service
@RequiredArgsConstructor
public class WalletService {

    private static final int MAX_RETRY = 5;

    private final WalletAccountRepository accountRepository;
    private final WalletTransactionRepository transactionRepository;

    /**
     * 재고 차감과 같은 이유로 둔다 — <b>재시도가 흡수하면 경합이 있었다는 사실이 사라진다.</b>
     * 최종 결과만 세면 다섯 번 만에 성공한 요청이 한 번에 성공한 요청과 구별되지 않는다.
     */
    private final io.micrometer.core.instrument.MeterRegistry meterRegistry;

    /** 선불 충전 — 전금법 기명 한도 초과 시 LIMIT_EXCEEDED. 낙관적 락 충돌은 재시도. 주문과 무관해 orderNo 없음. */
    public long charge(long userId, long amount) {
        return mutateWithRetry(userId, WalletTransactionType.CHARGE, amount, null, WalletAccount::charge);
    }

    /**
     * 잔액 차감(결제 예약) — 부족 시 INSUFFICIENT_BALANCE. 낙관적 락 충돌은 재시도.
     * <b>활성 예약</b>(USE−RESTORE−REFUND {@literal >} 0)이 남아 있으면 재차감하지 않고 현재 잔액을 반환한다
     * (중복요청·사가 재진입은 skip, 승인 실패로 해제된 뒤 재시도는 다시 차감).
     */
    public long use(long userId, long amount, String orderNo) {
        if (orderNo != null && refundableAmount(orderNo) > 0) {
            return balance(userId); // 활성 예약 있음 — 멱등 skip
        }
        return mutateWithRetry(userId, WalletTransactionType.USE, amount, orderNo, WalletAccount::use);
    }

    /**
     * 예약 해제(사가 보상) — 승인 실패·재고 부족 시 USE로 잡은 예약을 되돌린다. <b>주문 단위 멱등</b>:
     * 같은 주문의 RESTORE가 이미 있으면 skip(복구가 settle을 재실행해도 1회만). point.restore와 동일 계약.
     */
    public long restore(long userId, long amount, String orderNo) {
        if (orderNo != null && transactionRepository.existsByOrderNoAndType(orderNo, WalletTransactionType.RESTORE)) {
            return balance(userId); // 이미 해제됨 — 멱등 skip
        }
        return mutateWithRetry(userId, WalletTransactionType.RESTORE, amount, orderNo, WalletAccount::refund);
    }

    /**
     * 취소 환불 — 완료된 결제(PAID)를 취소할 때 월렛 몫을 되돌린다. <b>비멱등</b>: 한 주문에 부분취소가
     * 여러 번 올 수 있어 REFUND는 매번 반영한다(point.refund와 동일 계약). 낙관적 락 충돌은 재시도.
     */
    public long refund(long userId, long amount, String orderNo) {
        return mutateWithRetry(userId, WalletTransactionType.REFUND, amount, orderNo, WalletAccount::refund);
    }

    /**
     * 주문에 예약된 <b>순</b> 월렛 차감액 = USE − RESTORE − REFUND, 음수면 0. 크래시 복구가 카드/포인트/월렛
     * 몫을 역산할 때, 그리고 취소 시 환불 가능액 판정에 쓴다. 해제(RESTORE)·환불(REFUND)된 몫을 빼야
     * 유령 금액이 남지 않는다.
     */
    @Transactional(readOnly = true)
    public long reservedAmountForOrder(String orderNo) {
        return refundableAmount(orderNo);
    }

    /** 취소 시 환불 가능한 월렛 결제분 = USE 합 − RESTORE 합 − REFUND 합(음수 보정 0). */
    @Transactional(readOnly = true)
    public long refundableAmount(String orderNo) {
        long used = transactionRepository.sumAmountByOrderNoAndType(orderNo, WalletTransactionType.USE);
        long restored = transactionRepository.sumAmountByOrderNoAndType(orderNo, WalletTransactionType.RESTORE);
        long refunded = transactionRepository.sumAmountByOrderNoAndType(orderNo, WalletTransactionType.REFUND);
        return Math.max(0, used - restored - refunded);
    }

    /** 잔액 조회 — 계정이 없으면 0. */
    @Transactional(readOnly = true)
    public long balance(long userId) {
        return accountRepository.findById(userId)
                .map(WalletAccount::getBalance)
                .orElse(0L);
    }

    /** 잔액 + 최근 거래 이력 조회 — 사용자 월렛 화면용. */
    @Transactional(readOnly = true)
    public WalletView myWallet(long userId) {
        return WalletView.of(balance(userId), transactionRepository.findTop20ByUserIdOrderByIdDesc(userId));
    }

    @FunctionalInterface
    private interface BalanceMutation {
        void apply(WalletAccount account, long amount);
    }

    /**
     * 잔액 변경 + 이력 append를 낙관적 락 재시도와 함께 수행한다. 멱등 판정은 호출자(use/restore/refund)가
     * 각자의 계약대로 먼저 하고, 여기서는 순수 변경만 담당한다. 재시도 루프를 단일 {@code @Transactional}로
     * 감싸지 않는다 — 감싸면 첫 충돌에서 트랜잭션이 rollback-only가 되어 재시도가 무의미해지고 자기호출이라
     * 프록시도 우회된다. 각 시도의 repository 연산은 Spring Data 기본 트랜잭션 경계로 처리되고, @Version
     * 충돌은 saveAndFlush 시점에 감지되어 다음 시도로 넘어간다. 재시도를 소진하면 예외.
     */
    private long mutateWithRetry(long userId, WalletTransactionType type, long amount,
                                 String orderNo, BalanceMutation mutation) {
        int attempts = 0;
        while (true) {
            try {
                WalletAccount account = accountRepository.findById(userId)
                        .orElseGet(() -> WalletAccount.of(userId));
                mutation.apply(account, amount); // 한도/잔액 검증은 엔티티가 수행
                accountRepository.saveAndFlush(account); // 버전 충돌을 이 시점에 감지
                transactionRepository.save(WalletTransaction.of(userId, type, amount, account.getBalance(), orderNo));
                return account.getBalance();
            } catch (ObjectOptimisticLockingFailureException | OptimisticLockException e) {
                meterRegistry.counter("wallet.mutate.retry").increment();
                if (++attempts >= MAX_RETRY) {
                    meterRegistry.counter("wallet.mutate.exhausted").increment();
                    throw new WalletException("WALLET_CONCURRENCY",
                            "월렛 잔액 변경 경합이 계속됩니다: userId=" + userId);
                }
            }
        }
    }
}
