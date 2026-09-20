package com.beomsu.becommerce.payment.va;

import com.beomsu.becommerce.payment.PaymentException;
import com.beomsu.becommerce.shared.crypto.EncryptedStringConverter;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 가상계좌 애그리거트.
 *
 * <p>상태 전이는 도메인 메서드({@link #confirmDeposit}, {@link #reverseDeposit}, {@link #expire},
 * {@link #cancel})를 통해서만 일어나며, {@link VaStatus}의 허용 전이표를 위반하면 예외가 난다.
 * 낙관적 락({@code @Version})으로 동시 전이(만료 배치 vs 입금 웹훅 레이스)를 감지한다.
 */
@Entity
@Table(name = "virtual_accounts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VirtualAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String orderNo;

    @Column(length = 200)
    private String paymentKey;

    /** 은행 코드(토스페이먼츠 코드 체계) */
    @Column(nullable = false, length = 10)
    private String bankCode;

    /**
     * 계좌번호 — 민감정보라 저장 시 envelope 암호화(@Convert)한다. 값으로 조회하지 않으므로(orderNo·
     * paymentKey로만 찾음) 블라인드 인덱스 없이 단순 암호화만 적용한다. 암호문은 평문보다 훨씬 길어
     * 컬럼 길이를 255로 잡는다.
     */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(nullable = false, length = 255)
    private String accountNumber;

    @Column(nullable = false)
    private long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private VaStatus status;

    /** 입금기한 — 이 시각을 넘기면 만료 배치의 대상이 된다(EXPIRED 웹훅이 없기 때문). */
    @Column(nullable = false)
    private Instant dueDate;

    /** 입금 확인 시각 (미입금이면 null) */
    private Instant depositedAt;

    @Version
    private long version;

    @Column(nullable = false)
    private Instant createdAt;

    private VirtualAccount(String orderNo, String paymentKey, String bankCode,
                           String accountNumber, long amount, Instant dueDate) {
        this.orderNo = orderNo;
        this.paymentKey = paymentKey;
        this.bankCode = bankCode;
        this.accountNumber = accountNumber;
        this.amount = amount;
        this.dueDate = dueDate;
        this.status = VaStatus.WAITING_FOR_DEPOSIT;
        this.createdAt = Instant.now();
    }

    /** 가상계좌 발급 — WAITING_FOR_DEPOSIT 상태로 생성한다. */
    public static VirtualAccount issue(String orderNo, String paymentKey, String bankCode,
                                       String accountNumber, long amount, Instant dueDate) {
        return new VirtualAccount(orderNo, paymentKey, bankCode, accountNumber, amount, dueDate);
    }

    /** WAITING_FOR_DEPOSIT → DONE. 입금 확인(조회 재검증 후 호출). */
    public void confirmDeposit() {
        transitionTo(VaStatus.DONE);
        this.depositedAt = Instant.now();
    }

    /**
     * DONE → WAITING_FOR_DEPOSIT 역전이. 일부 은행이 입금 실패를 뒤늦게 통보(지연 통보)할 때,
     * 먼저 온 DONE을 되돌린다. 입금 시각도 함께 지운다.
     */
    public void reverseDeposit(String reason) {
        transitionTo(VaStatus.WAITING_FOR_DEPOSIT);
        this.depositedAt = null;
    }

    /** WAITING_FOR_DEPOSIT → EXPIRED. 입금기한 경과(만료 배치가 조회 재검증 후 호출). */
    public void expire() {
        transitionTo(VaStatus.EXPIRED);
    }

    /** → CANCELED. 입금 전 전액취소 또는 지연 통보 후 취소. */
    public void cancel() {
        transitionTo(VaStatus.CANCELED);
    }

    private void transitionTo(VaStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new PaymentException("INVALID_STATE_TRANSITION",
                    "허용되지 않은 가상계좌 상태 전이입니다: %s → %s".formatted(status, target));
        }
        this.status = target;
    }
}
