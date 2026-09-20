package com.beomsu.becommerce.personalization.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 사용자 활동 한 건(합성). E1의 이벤트 원천이고 E2가 "동일 로그"로 재생할 원본이다.
 *
 * <p>{@code uk_user_activity_seq (userId, seq)}가 두 일을 한 번에 맡는다 — 순서 판정의 기준이고,
 * 컨슈머가 at-least-once로 같은 이벤트를 다시 받아도 DB가 1건으로 유지되는 근거다.
 *
 * <p>{@code itemId}는 {@code products}를 논리적으로 가리키지만 <b>검증하지 않는다</b>. 합성 이벤트가
 * 실제 상품을 가리키지 않아도 컨텍스트 실험에는 무관하고, 여기서 상품을 읽으면 개인화 경계 규칙
 * ("커머스 도메인 테이블 직접 조회 금지")에 걸린다.
 */
@Entity
@Table(
        name = "user_activities",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_user_activity_seq",
                columnNames = {"userId", "seq"})
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserActivity {

    /** 실제 사용자 행동이 아니라 생성기가 흘린 것이다. 실데이터와 섞지 않기 위해 컬럼으로 남긴다. */
    public static final String SOURCE_SYNTHETIC = "SYNTHETIC";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 인증 principal에서 얻은 값. 클라이언트가 보낸 userId는 쓰지 않는다. */
    @Column(nullable = false)
    private long userId;

    @Column(nullable = false)
    private long itemId;

    /** CLICK / VIEW. */
    @Column(nullable = false, length = 20)
    private String activityType;

    /** 사용자별 단조 증가 순번 — 생성기가 부여한다. */
    @Column(nullable = false)
    private long seq;

    @Column(nullable = false, length = 20)
    private String source;

    @Column(nullable = false)
    private Instant occurredAt;

    @Column(nullable = false)
    private Instant createdAt;

    private UserActivity(long userId, long itemId, String activityType, long seq, Instant occurredAt) {
        this.userId = userId;
        this.itemId = itemId;
        this.activityType = activityType;
        this.seq = seq;
        this.source = SOURCE_SYNTHETIC;
        this.occurredAt = occurredAt;
        this.createdAt = Instant.now();
    }

    public static UserActivity of(long userId, long itemId, String activityType, long seq, Instant occurredAt) {
        return new UserActivity(userId, itemId, activityType, seq, occurredAt);
    }
}
