package com.beomsu.becommerce.personalization.internal;

import com.beomsu.becommerce.personalization.UserActivityEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 활동 수집 — 저장하고 이벤트를 발행한다.
 *
 * <p><b>같은 트랜잭션에서 저장과 발행을 함께 한다.</b> 이게 Outbox가 유실을 막는 방식이다 —
 * 트랜잭션이 커밋되면 이벤트도 {@code event_publication}에 함께 남고, 발행이 실패하면 재기동 시
 * 다시 나간다(이 저장소의 기존 경로를 그대로 쓴다).
 *
 * <p><b>{@code IN_REQUEST}일 때만 여기서 컨텍스트까지 갱신한다.</b> 그 모드의 정의가 그것이고,
 * 대가도 거기서 나온다 — 컨텍스트 저장소 쓰기가 실패하면 <b>활동 기록까지 함께 롤백된다</b>.
 * E1-b가 재는 "결합"이 이 한 줄이다. 나머지 두 방식은 커밋 뒤로 미루므로 쓰기 경로가 저장소
 * 장애에 영향받지 않는다.
 *
 * <p>이벤트는 <b>모드와 무관하게 항상 발행</b>한다. {@code IN_PROCESS}·{@code IN_REQUEST}에서는
 * 지금 소비자가 없지만, 프로세스 밖 소비자가 생겼을 때 경로가 이미 열려 있게 하려는 것이다.
 * 그 "옵션의 값"은 이 실험이 재지 못한 항목이다.
 */
@Service
@Transactional
public class ActivityIngestService {

    private final UserActivityRepository activityRepository;
    private final ApplicationEventPublisher events;
    private final ContextApplier contextApplier;
    private final ContextTransport transport;

    public ActivityIngestService(UserActivityRepository activityRepository,
                                 ApplicationEventPublisher events,
                                 ContextApplier contextApplier,
                                 @Value("${app.personalization.transport:KAFKA}") ContextTransport transport) {
        this.activityRepository = activityRepository;
        this.events = events;
        this.contextApplier = contextApplier;
        this.transport = transport;
    }

    public UserActivity ingest(long userId, long itemId, String activityType, long seq) {
        UserActivity activity = UserActivity.of(userId, itemId, activityType, seq, Instant.now());
        activityRepository.save(activity);

        UserActivityEvent event = new UserActivityEvent(userId, itemId, activityType, seq,
                activity.getOccurredAt(), UserActivity.SOURCE_SYNTHETIC);
        events.publishEvent(event);

        if (transport == ContextTransport.IN_REQUEST) {
            contextApplier.apply(event);
        }
        return activity;
    }
}
