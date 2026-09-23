package com.beomsu.becommerce.point.internal;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 포인트 계정 — 사용자별 잔액의 소유자.
 *
 * <p>잔액 변경은 {@link #use}/{@link #restore}를 통해서만 일어나며, 차감 결과가 음수가 되는 것을
 * 엔티티 수준에서 차단한다({@link PointException#insufficient}). 낙관적 락({@code @Version})으로
 * 동시 차감 경합을 감지한다.
 */
@Entity
@Table(name = "point_accounts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PointAccount {

    /** 사용자 식별자 = PK. 한 사용자당 하나의 계정. */
    @Id
    private long userId;

    @Column(nullable = false)
    private long balance;

    @Version
    private long version;

    private PointAccount(long userId, long balance) {
        if (balance < 0) {
            throw new IllegalArgumentException("초기 잔액은 음수일 수 없습니다: " + balance);
        }
        this.userId = userId;
        this.balance = balance;
    }

    public static PointAccount of(long userId, long balance) {
        return new PointAccount(userId, balance);
    }

    /** 포인트 차감. 잔액보다 크면 예외 — 마이너스 잔액을 만들 수 없다. */
    public void use(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("사용 금액은 음수일 수 없습니다: " + amount);
        }
        if (balance < amount) {
            throw PointException.insufficient(balance, amount);
        }
        this.balance -= amount;
    }

    /** 포인트 복원/환불 — 차감분을 다시 더한다(카드 실패 보상, 부분취소 환불). */
    public void restore(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("복원 금액은 음수일 수 없습니다: " + amount);
        }
        this.balance += amount;
    }

    /** 포인트 적립 — 결제 완료 시 실결제액 기준으로 잔액을 더한다. */
    public void earn(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("적립 금액은 음수일 수 없습니다: " + amount);
        }
        this.balance += amount;
    }

    /**
     * 적립 회수 — 취소 시 적립분을 되돌린다. {@link #use}와 달리 <b>잔액이 음수가 되는 것을 허용</b>한다:
     * 이미 적립분을 소진한 뒤 취소해도 회수를 관철해야 파밍을 막을 수 있고, 음수분(적립 채무)은 이후
     * 적립으로 상계된다. (일반 결제 차감 {@code use}는 여전히 잔액 부족을 막는다 — 회수만 예외.)
     */
    public void reverseEarn(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("회수 금액은 음수일 수 없습니다: " + amount);
        }
        this.balance -= amount;
    }
}
