package com.beomsu.becommerce.notification.consumption;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 처리 완료한 이벤트 마커 — 멱등 컨슈머의 핵심.
 *
 * <p>Outbox는 at-least-once라 같은 이벤트가 두 번 올 수 있다. (eventKey, consumer) 유니크로
 * "이미 처리했는가"를 판별해 중복 처리를 막는다.
 */
@Entity
@Table(name = "processed_events",
        uniqueConstraints = @UniqueConstraint(name = "uk_processed", columnNames = {"eventKey", "consumer"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
// 같은 모듈의 다른 패키지가 참조하므로 public 이다. 모듈 밖 접근은 ModularityTests 의
// allowedDependencies 가 막는다.
public class ProcessedEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String eventKey;

    @Column(nullable = false, length = 100)
    private String consumer;

    @Column(nullable = false)
    private Instant processedAt;

    private ProcessedEvent(String eventKey, String consumer) {
        this.eventKey = eventKey;
        this.consumer = consumer;
        this.processedAt = Instant.now();
    }

    public static ProcessedEvent of(String eventKey, String consumer) {
        return new ProcessedEvent(eventKey, consumer);
    }
}
