package com.beomsu.becommerce.wallet.internal;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 선불 월렛 계정 — 사용자별 페이머니 잔액의 소유자.
 *
 * <p>잔액 변경은 {@link #charge}/{@link #use}/{@link #refund}를 통해서만 일어난다. 충전은 전금법
 * 기명 한도({@link WalletException#MAX_BALANCE})를, 차감은 마이너스 잔액을 엔티티 수준에서 차단한다.
 * 낙관적 락({@code @Version})으로 동시 차감 경합을 감지한다 — 서비스가 충돌 시 재시도한다.
 */
@Entity
@Table(name = "wallet_accounts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WalletAccount {

    /** 사용자 식별자 = PK. 한 사용자당 하나의 월렛. */
    @Id
    private long userId;

    @Column(nullable = false)
    private long balance;

    @Version
    private long version;

    private WalletAccount(long userId, long balance) {
        if (balance < 0) {
            throw new IllegalArgumentException("초기 잔액은 음수일 수 없습니다: " + balance);
        }
        this.userId = userId;
        this.balance = balance;
    }

    /** 잔액 0인 새 월렛을 만든다. */
    public static WalletAccount of(long userId) {
        return new WalletAccount(userId, 0);
    }

    /**
     * 선불 충전. 충전 후 잔액이 전금법 기명 한도({@link WalletException#MAX_BALANCE})를 넘으면
     * LIMIT_EXCEEDED 예외 — 규제를 도메인 규칙으로 강제한다.
     */
    public void charge(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("충전 금액은 음수일 수 없습니다: " + amount);
        }
        if (balance + amount > WalletException.MAX_BALANCE) {
            throw WalletException.limitExceeded(balance, amount);
        }
        this.balance += amount;
    }

    /** 잔액 차감(결제). 잔액보다 크면 INSUFFICIENT_BALANCE 예외 — 마이너스 잔액을 만들 수 없다. */
    public void use(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("사용 금액은 음수일 수 없습니다: " + amount);
        }
        if (balance < amount) {
            throw WalletException.insufficientBalance(balance, amount);
        }
        this.balance -= amount;
    }

    /**
     * 환불 — 취소된 결제의 차감분을 잔액에 되돌린다.
     *
     * <p>환불은 이미 정상적으로 충전·사용했던 금액을 원복하는 것이므로, 충전과 달리 한도 검증을
     * 완화한다(환불로 순간적으로 한도를 넘더라도 원래 사용자 소유였던 자금의 복원이기 때문).
     */
    public void refund(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("환불 금액은 음수일 수 없습니다: " + amount);
        }
        this.balance += amount;
    }
}
