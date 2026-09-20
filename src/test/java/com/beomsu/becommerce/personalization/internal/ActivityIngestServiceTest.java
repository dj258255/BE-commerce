package com.beomsu.becommerce.personalization.internal;

import com.beomsu.becommerce.personalization.UserActivityEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 수집 경로가 <b>전달 방식에 따라 무엇을 하는지</b>를 고정한다.
 *
 * <p>가장 중요한 것은 마지막 테스트다 — {@code IN_REQUEST}는 컨텍스트 쓰기 실패가 <b>활동 기록까지
 * 롤백시키는</b> 유일한 방식이고, 이게 E1-b가 재는 결합의 실체다. 나머지 둘은 커밋 뒤로 미루므로
 * 쓰기 경로가 저장소 장애에 영향받지 않는다.
 */
class ActivityIngestServiceTest {

    private static final long USER = 1L;

    private final UserActivityRepository repository = mock(UserActivityRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final ContextApplier applier = mock(ContextApplier.class);

    private ActivityIngestService service(ContextTransport transport) {
        return new ActivityIngestService(repository, events, applier, transport);
    }

    @Test
    @DisplayName("IN_REQUEST는 같은 트랜잭션에서 컨텍스트까지 갱신한다")
    void inRequestAppliesInline() {
        service(ContextTransport.IN_REQUEST).ingest(USER, 10L, "CLICK", 1L);

        verify(repository).save(any(UserActivity.class));
        verify(events).publishEvent(any(UserActivityEvent.class));
        verify(applier).apply(any(UserActivityEvent.class));
    }

    @Test
    @DisplayName("IN_REQUEST에서 컨텍스트 쓰기가 실패하면 예외가 올라온다 — 활동 기록도 함께 롤백된다")
    void inRequestPropagatesContextFailure() {
        when(applier.apply(any(UserActivityEvent.class))).thenThrow(new IllegalStateException("저장소 장애"));

        assertThatThrownBy(() -> service(ContextTransport.IN_REQUEST).ingest(USER, 10L, "CLICK", 1L))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("KAFKA는 저장과 발행만 한다 — 적용은 브로커를 거쳐 컨슈머가 맡는다")
    void kafkaDoesNotApplyInline() {
        service(ContextTransport.KAFKA).ingest(USER, 10L, "CLICK", 1L);

        verify(repository).save(any(UserActivity.class));
        verify(events).publishEvent(any(UserActivityEvent.class));
        verify(applier, never()).apply(any());
    }

    @Test
    @DisplayName("IN_PROCESS는 저장과 발행만 한다 — 적용은 커밋 뒤 리스너가 맡는다")
    void inProcessDoesNotApplyInline() {
        service(ContextTransport.IN_PROCESS).ingest(USER, 10L, "CLICK", 1L);

        verify(events).publishEvent(any(UserActivityEvent.class));
        verify(applier, never()).apply(any());
    }

    @Test
    @DisplayName("전달 방식과 무관하게 이벤트는 항상 발행된다 — 프로세스 밖 소비자의 경로를 닫지 않는다")
    void eventIsAlwaysPublished() {
        for (ContextTransport transport : ContextTransport.values()) {
            service(transport).ingest(USER, 10L, "CLICK", 1L);
        }

        verify(events, org.mockito.Mockito.times(ContextTransport.values().length))
                .publishEvent(any(UserActivityEvent.class));
    }
}
