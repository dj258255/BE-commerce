package com.beomsu.becommerce.order.compensation;

import static org.mockito.ArgumentMatchers.any;

import org.springframework.data.domain.Pageable;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CompensationServiceTest {

    private CompensationTaskRepository repository;
    private CompensationExecutor executor;
    private CompensationService service;

    @BeforeEach
    void setUp() {
        repository = mock(CompensationTaskRepository.class);
        executor = mock(CompensationExecutor.class);
        service = new CompensationService(repository, executor);
    }

    private CompensationTask taskWithId(long id) {
        CompensationTask task = mock(CompensationTask.class);
        when(task.getId()).thenReturn(id);
        return task;
    }

    @Test
    @DisplayName("적재: 망취소 보상 태스크를 저장한다")
    void enqueuePersistsTask() {
        service.enqueueNetworkCancel("ord-1", 14_000, "재고 부족: 카드 승인 후 자동 망취소");
        verify(repository).save(any(CompensationTask.class));
    }

    @Test
    @DisplayName("processPending: due 태스크마다 attempt 호출, 성공 카운트 반환")
    void processPendingAttemptsEachDueTask() {
        CompensationTask t1 = taskWithId(1L);
        CompensationTask t2 = taskWithId(2L);
        when(repository.findByStatusAndNextAttemptAtBefore(eq(CompensationStatus.PENDING), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(t1, t2));

        int success = service.processPending();

        verify(executor).attempt(1L);
        verify(executor).attempt(2L);
        verify(executor, never()).recordFailure(anyLong(), anyString());
        assertThat(success).isEqualTo(2);
    }

    @Test
    @DisplayName("processPending: 한 건 실패 시 recordFailure로 격리하고 배치는 계속된다")
    void processPendingIsolatesFailure() {
        CompensationTask t1 = taskWithId(1L);
        CompensationTask t2 = taskWithId(2L);
        when(repository.findByStatusAndNextAttemptAtBefore(eq(CompensationStatus.PENDING), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(t1, t2));
        doThrow(new RuntimeException("PG down")).when(executor).attempt(1L); // 첫 건 실패

        int success = service.processPending();

        verify(executor).recordFailure(1L, "PG down"); // 실패는 별도 tx로 기록
        verify(executor).attempt(2L);                  // 다음 건은 계속 처리
        assertThat(success).isEqualTo(1);              // 성공 1건만 집계
    }
}
