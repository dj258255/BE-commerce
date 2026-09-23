package com.beomsu.becommerce.notification.consumption;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 죽은 메시지(Dead Letter). 컨슈머 처리가 실패한 이벤트를 격리해, 리스너가 계속 터지며
 * 이벤트 처리를 막는 것을 방지한다. 재처리에 필요한 이벤트 데이터를 함께 보관해, 백오피스
 * 어드민이 나중에 다시 시도할 수 있게 한다.
 */
@Entity
@Table(name = "dead_letters")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
// 같은 모듈의 다른 패키지가 참조하므로 public 이다. 모듈 밖 접근은 ModularityTests 의
// allowedDependencies 가 막는다.
public class DeadLetter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String eventType;

    @Column(nullable = false, length = 200)
    private String eventKey;

    // --- 재처리에 필요한 이벤트 데이터 ---
    @Column(nullable = false, length = 64)
    private String orderNo;

    @Column(nullable = false)
    private Long paymentId;

    @Column(nullable = false)
    private long amount;

    @Column(length = 1000)
    private String failReason;

    @Column(nullable = false)
    private int retryCount;

    @Column(nullable = false)
    private Instant createdAt;

    private DeadLetter(String eventType, String eventKey, String orderNo, Long paymentId,
                       long amount, String failReason) {
        this.eventType = eventType;
        this.eventKey = eventKey;
        this.orderNo = orderNo;
        this.paymentId = paymentId;
        this.amount = amount;
        this.failReason = failReason;
        this.retryCount = 0;
        this.createdAt = Instant.now();
    }

    public static DeadLetter of(String eventType, String eventKey, String orderNo, Long paymentId,
                         long amount, String failReason) {
        return new DeadLetter(eventType, eventKey, orderNo, paymentId, amount, failReason);
    }

    public void incrementRetry() {
        this.retryCount++;
    }
}
