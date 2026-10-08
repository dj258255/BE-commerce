package com.beomsu.becommerce.shorts;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 숏폼 시청 신호 한 건(R27) — 영상 하나를 본 한 번의 시청(세션)을 요약해 담는다.
 *
 * <p>이 저장소의 기존 "개인화 이벤트 경로"({@code com.beomsu.becommerce.personalization},
 * {@code user_activities} → Kafka/CDC)는 재사용하지 않는다. 그 경로는 명시적으로 <b>합성
 * 생성기 전용</b>이고({@code UserActivity.SOURCE_SYNTHETIC}, {@code PersonalizationController}의
 * javadoc: "실제 화면이 부르는 표면이 아니다"), 로그인 사용자만 허용하며({@code hasRole("USER")}),
 * 신호도 {@code CLICK}·{@code VIEW} 두 가지뿐이다 — R27이 요구하는 비로그인 익명 식별자·시청
 * 시간·완료·다시보기·건너뛰기·상품탭 신호를 담을 수 없다. 대신 이 저장소가 관측 데이터에
 * 실제로 적용한 선례(ADR-043 홈 노출 로그: 요청 단위 한 행, Outbox/Kafka에 태우지 않음, 기록
 * 실패가 화면을 죽이지 않음)를 media 모듈 자신의 표로 따른다. 근거는 ADR-083에 적었다.
 *
 * <p>{@code userId}·{@code anonymousId}는 정확히 하나만 채워진다({@link ViewerIdentity}와 같은 규칙).
 * FK를 걸지 않는다 — 이 저장소의 "논리적 FK + 인덱스" 관례({@code user_activities}·
 * {@code home_impressions}와 같다).
 */
@Entity
@Table(name = "short_view_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ShortViewEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "short_video_id", nullable = false)
    private long shortVideoId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "anonymous_id", length = 64)
    private String anonymousId;

    @Column(name = "watch_seconds", nullable = false)
    private int watchSeconds;

    @Column(nullable = false)
    private boolean completed;

    @Column(name = "replay_count", nullable = false)
    private int replayCount;

    @Column(name = "skipped_within3s", nullable = false)
    private boolean skippedWithin3s;

    @Column(name = "product_tag_tapped", nullable = false)
    private boolean productTagTapped;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    private ShortViewEvent(long shortVideoId, ViewerIdentity viewer, int watchSeconds, boolean completed,
            int replayCount, boolean skippedWithin3s, boolean productTagTapped, Instant occurredAt) {
        this.shortVideoId = shortVideoId;
        this.userId = viewer.userId();
        this.anonymousId = viewer.anonymousId();
        this.watchSeconds = watchSeconds;
        this.completed = completed;
        this.replayCount = replayCount;
        this.skippedWithin3s = skippedWithin3s;
        this.productTagTapped = productTagTapped;
        this.occurredAt = occurredAt;
        this.createdAt = occurredAt;
    }

    /**
     * 시청 신호 한 건을 만든다. {@code occurredAt}은 <b>서버가 받은 시각</b>이다(클라이언트 시계를
     * 신뢰하지 않는다 — userId를 principal에서 얻는 것과 같은 원칙).
     */
    public static ShortViewEvent record(long shortVideoId, ViewerIdentity viewer, int watchSeconds,
            boolean completed, int replayCount, boolean skippedWithin3s, boolean productTagTapped,
            Instant occurredAt) {
        return new ShortViewEvent(shortVideoId, viewer, watchSeconds, completed, replayCount,
                skippedWithin3s, productTagTapped, occurredAt);
    }
}
