package com.beomsu.becommerce.point;

import com.beomsu.becommerce.point.internal.PointView;
import com.beomsu.becommerce.point.internal.PointHistoryType;
import com.beomsu.becommerce.point.internal.PointHistoryRepository;
import com.beomsu.becommerce.point.internal.PointHistory;
import com.beomsu.becommerce.point.internal.PointException;
import com.beomsu.becommerce.point.internal.PointAccountRepository;
import com.beomsu.becommerce.point.internal.PointAccount;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 포인트 애플리케이션 서비스 — point 모듈의 공개 진입점.
 *
 * <p>복합결제 Saga에서 order 모듈이 호출한다: 결제 시 {@link #use}(선점), 카드 실패 시
 * {@link #restore}(보상), 부분취소 시 {@link #refund}. 모든 잔액 변경은 이력 append와 함께 이뤄지며,
 * (orderNo, type)로 멱등성을 보장해 "따닥" 중복 요청·재시도에도 이중 차감/복원이 없도록 한다.
 *
 * <p><b>잔액 변경은 {@code saveAndFlush}로 즉시 영속한다</b>(pay-26 교훈). 취소처럼 같은 트랜잭션에서
 * 앞서 readOnly 조회({@link #refundableAmount})가 세션 FlushMode를 MANUAL로 바꾼 뒤라면, 일반 {@code save}는
 * 지연 flush에 의존해 UPDATE가 유실될 수 있다(이력 INSERT는 IDENTITY라 즉시 flush돼 남아 불일치가 생긴다).
 */
@Service
@Transactional
@RequiredArgsConstructor
public class PointService {

    private final PointAccountRepository accountRepository;
    private final PointHistoryRepository historyRepository;

    /**
     * 포인트 차감(결제 선점). <b>활성 예약</b>(USE−RESTORE−REFUND {@literal >} 0)이 남아 있으면 멱등 skip한다
     * — 중복요청·사가 재진입은 재차감하지 않되, 승인 실패로 예약이 RESTORE된 뒤 같은 주문을 재시도하면
     * 다시 차감돼야 하기 때문이다(거절→재시도 이중무료 방지). 롤백이 확실한 내부 자원이라 카드보다 먼저 선점된다.
     */
    public void use(long userId, long amount, String orderNo) {
        if (amount < 0) {
            throw new PointException("INVALID_AMOUNT", "포인트 금액은 음수일 수 없습니다: " + amount);
        }
        if (amount == 0) {
            return;
        }
        if (refundableAmount(orderNo) > 0) {
            return; // 활성 예약 있음 — 멱등 skip
        }
        PointAccount account = accountRepository.findById(userId)
                .orElseGet(() -> PointAccount.of(userId, 0));
        account.use(amount); // 잔액 부족이면 여기서 INSUFFICIENT_POINT
        accountRepository.saveAndFlush(account);
        historyRepository.save(PointHistory.of(userId, PointHistoryType.USE, amount, orderNo));
    }

    /**
     * 포인트 복원(보상 트랜잭션). 카드 승인 실패 시 {@link #use}로 차감했던 포인트를 되돌린다.
     * 같은 주문의 RESTORE 이력이 이미 있으면 멱등하게 skip한다.
     */
    public void restore(long userId, long amount, String orderNo) {
        if (amount < 0) {
            throw new PointException("INVALID_AMOUNT", "포인트 금액은 음수일 수 없습니다: " + amount);
        }
        if (amount == 0) {
            return;
        }
        if (historyRepository.existsByOrderNoAndType(orderNo, PointHistoryType.RESTORE)) {
            return; // 멱등: 이미 복원함
        }
        PointAccount account = accountRepository.findById(userId)
                .orElseGet(() -> PointAccount.of(userId, 0));
        account.restore(amount);
        accountRepository.saveAndFlush(account);
        historyRepository.save(PointHistory.of(userId, PointHistoryType.RESTORE, amount, orderNo));
    }

    /**
     * 포인트 환불(부분취소). 취소된 결제에서 포인트로 결제했던 몫을 되돌린다.
     * 부분취소는 한 주문에 여러 번 발생할 수 있으므로 REFUND는 멱등 skip하지 않는다.
     */
    public void refund(long userId, long amount, String orderNo) {
        if (amount < 0) {
            throw new PointException("INVALID_AMOUNT", "포인트 금액은 음수일 수 없습니다: " + amount);
        }
        if (amount == 0) {
            return;
        }
        PointAccount account = accountRepository.findById(userId)
                .orElseGet(() -> PointAccount.of(userId, 0));
        account.restore(amount);
        accountRepository.saveAndFlush(account);
        historyRepository.save(PointHistory.of(userId, PointHistoryType.REFUND, amount, orderNo));
    }

    /**
     * 주문의 <b>활성 예약</b> 포인트 = USE 합 − RESTORE 합 − REFUND 합(음수 보정 0). 취소 시 환불 가능액이자
     * {@link #use}의 멱등 판정 기준이다 — 사가 해제(RESTORE)·취소 환불(REFUND)된 몫을 빼야 재시도·취소가 정확하다.
     */
    @Transactional(readOnly = true)
    public long refundableAmount(String orderNo) {
        long used = historyRepository.sumAmountByOrderNoAndType(orderNo, PointHistoryType.USE);
        long restored = historyRepository.sumAmountByOrderNoAndType(orderNo, PointHistoryType.RESTORE);
        long refunded = historyRepository.sumAmountByOrderNoAndType(orderNo, PointHistoryType.REFUND);
        return Math.max(0, used - restored - refunded);
    }

    /**
     * 포인트 적립 — 결제 완료 시 실결제액 기준으로 적립한다. 같은 주문의 EARN 이력이 이미 있으면
     * 멱등하게 skip한다(사가 성공분기·복구 재실행에도 이중적립 방지). amount==0이면 무시.
     *
     * <p><b>원자 증가로 적립한다(경합 실측 후 전환)</b>: 이전에는 엔티티를 읽어 고쳐 쓰는
     * 낙관적 락 경로였는데, 같은 계정의 동시 결제에서 {@code @Version} 충돌이 결제 승인까지
     * 실패시키는 것을 부하 실험으로 확인했다(30VU 단일 계정, 승인 성공률 39.6% — 운영에선
     * per-user rate limit이 이 경합을 가려 왔다). 적립은 더하기라 순서가 무관하므로 DB 원자
     * 증가(upsert)로 바꿔 경합을 제거했다. 분석·실측: docs/performance/point-accrual-contention.md
     */
    public void earn(long userId, long amount, String orderNo) {
        if (amount < 0) {
            throw new PointException("INVALID_AMOUNT", "포인트 금액은 음수일 수 없습니다: " + amount);
        }
        if (amount == 0) {
            return;
        }
        if (historyRepository.existsByOrderNoAndType(orderNo, PointHistoryType.EARN)) {
            return; // 멱등: 이미 적립함
        }
        accountRepository.increaseBalanceAtomically(userId, amount);
        historyRepository.save(PointHistory.of(userId, PointHistoryType.EARN, amount, orderNo));
    }

    /**
     * 적립 회수(취소 보상) — 취소로 되돌린 실결제액 몫만큼 적립을 회수한다. 아직 회수되지 않은 적립분
     * (EARN 합 − EARN_REVERSAL 합)을 상한으로 캡해, 부분취소가 여러 번 와도 적립보다 많이 회수하지 않는다.
     * 이미 적립분을 소진했으면 잔액이 음수가 될 수 있다(파밍 방지 — 이후 적립으로 상계).
     */
    public void reverseEarn(long userId, long requestedAmount, String orderNo) {
        if (requestedAmount <= 0) {
            return;
        }
        long earned = historyRepository.sumAmountByOrderNoAndType(orderNo, PointHistoryType.EARN);
        long reversed = historyRepository.sumAmountByOrderNoAndType(orderNo, PointHistoryType.EARN_REVERSAL);
        long clawback = Math.min(requestedAmount, earned - reversed);
        if (clawback <= 0) {
            return; // 회수할 적립분 없음
        }
        PointAccount account = accountRepository.findById(userId)
                .orElseGet(() -> PointAccount.of(userId, 0));
        account.reverseEarn(clawback); // 잔액 음수 허용
        accountRepository.saveAndFlush(account);
        historyRepository.save(PointHistory.of(userId, PointHistoryType.EARN_REVERSAL, clawback, orderNo));
    }

    /** 잔액 조회 — 계정이 없으면 0. */
    @Transactional(readOnly = true)
    public long balance(long userId) {
        return accountRepository.findById(userId)
                .map(PointAccount::getBalance)
                .orElse(0L);
    }

    /** 잔액 + 최근 이력(적립·사용·복원·환불) 조회 — 사용자 포인트 화면용. */
    @Transactional(readOnly = true)
    public PointView myPoints(long userId) {
        return PointView.of(balance(userId), historyRepository.findTop20ByUserIdOrderByIdDesc(userId));
    }
}
