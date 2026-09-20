package com.beomsu.becommerce.shared.outbox;

import com.beomsu.becommerce.shared.outbox.OutboxCleanupScheduler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.events.CompletedEventPublications;
import org.springframework.modulith.events.EventPublication;

import java.time.Duration;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OutboxCleanupSchedulerTest {

    @Test
    @DisplayName("run()은 보존기간(Duration.ofDays)으로 완료 발행분 정리를 위임한다")
    void delegatesDeletion() {
        CompletedEventPublications completed = mock(CompletedEventPublications.class);
        // findAll()은 Collection<? extends EventPublication> — 와일드카드 캡처 때문에 doReturn으로 stub.
        doReturn(List.of()).when(completed).findAll();

        new OutboxCleanupScheduler(completed, 7L).run();

        verify(completed).deletePublicationsOlderThan(Duration.ofDays(7));
    }

    @Test
    @DisplayName("before/after 총량 차로 삭제 수를 산출한다(로깅용) — 삭제 호출은 항상 수행")
    void computesDeletedCount() {
        CompletedEventPublications completed = mock(CompletedEventPublications.class);
        // 삭제 전 2건 → 삭제 후 0건
        doReturn(List.of(mock(EventPublication.class), mock(EventPublication.class)),
                List.of()).when(completed).findAll();

        new OutboxCleanupScheduler(completed, 3L).run();

        verify(completed).deletePublicationsOlderThan(any(Duration.class));
        verify(completed, times(2)).findAll();
    }
}
