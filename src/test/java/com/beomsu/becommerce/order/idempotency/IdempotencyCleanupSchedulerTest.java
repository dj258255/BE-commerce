package com.beomsu.becommerce.order.idempotency;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class IdempotencyCleanupSchedulerTest {

    private final IdempotencyRepository repository = mock(IdempotencyRepository.class);
    private final IdempotencyCleanupScheduler scheduler = new IdempotencyCleanupScheduler(repository);

    @Test
    @DisplayName("유효기간 지난 멱등 레코드를 벌크 삭제한다")
    void purgesExpired() {
        when(repository.deleteByExpiresAtBefore(any(Instant.class))).thenReturn(3);

        scheduler.run();

        // threshold는 '지금' — expiresAt < now 인 레코드(유효기간 만료분)만 삭제 대상.
        verify(repository, times(1)).deleteByExpiresAtBefore(any(Instant.class));
    }

    @Test
    @DisplayName("삭제 대상이 없어도 예외 없이 통과한다")
    void noExpiredIsSafe() {
        when(repository.deleteByExpiresAtBefore(any(Instant.class))).thenReturn(0);

        scheduler.run();

        verify(repository).deleteByExpiresAtBefore(any(Instant.class));
    }
}
