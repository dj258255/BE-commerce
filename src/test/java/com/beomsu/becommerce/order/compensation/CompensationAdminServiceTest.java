package com.beomsu.becommerce.order.compensation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class CompensationAdminServiceTest {

    private CompensationTaskRepository repository;
    private CompensationExecutor executor;
    private CompensationAdminService service;

    @BeforeEach
    void setUp() {
        repository = mock(CompensationTaskRepository.class);
        executor = mock(CompensationExecutor.class);
        service = new CompensationAdminService(repository, executor);
    }

    /** maxRetries(5)만큼 실패시켜 FAILED로 소진된 태스크를 만든다. */
    private CompensationTask failedTask() {
        CompensationTask t = CompensationTask.networkCancel("ord-1", 14_000, "재고 부족: 자동 망취소");
        for (int i = 0; i < 5; i++) {
            t.recordFailure("일시 오류", Instant.now().plusSeconds(60));
        }
        return t;
    }

    @Test
    @DisplayName("list(FAILED): 상태별 태스크 페이지를 뷰로 매핑한다")
    void listMapsToView() {
        CompensationTask t = failedTask();
        Pageable pageable = PageRequest.of(0, 20);
        when(repository.findByStatus(eq(CompensationStatus.FAILED), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(t), pageable, 1));

        Page<CompensationTaskView> views = service.list(CompensationStatus.FAILED, pageable);

        assertThat(views).hasSize(1);
        CompensationTaskView v = views.getContent().get(0);
        assertThat(v.orderNo()).isEqualTo("ord-1");
        assertThat(v.amount()).isEqualTo(14_000);
        assertThat(v.status()).isEqualTo(CompensationStatus.FAILED);
        assertThat(v.retryCount()).isEqualTo(5);
        assertThat(v.lastError()).isEqualTo("일시 오류");
    }

    @Test
    @DisplayName("retry 성공: reopen(PENDING·retryCount 0) 저장 후 attempt가 예외 없이 끝나면 true")
    void retrySucceeds() {
        CompensationTask t = failedTask();
        when(repository.findById(7L)).thenReturn(Optional.of(t));
        // executor.attempt는 예외 없이 반환(= 성공)

        boolean ok = service.retry(7L);

        assertThat(ok).isTrue();
        // 재무장이 저장됐고(PENDING·retryCount 0), 즉시 시도가 호출됐다.
        assertThat(t.getStatus()).isEqualTo(CompensationStatus.PENDING);
        assertThat(t.getRetryCount()).isEqualTo(0);
        verify(repository).saveAndFlush(t);
        verify(executor).attempt(7L);
        verify(executor, never()).recordFailure(anyLong(), anyString());
    }

    @Test
    @DisplayName("retry 재실패: attempt가 예외를 던지면 recordFailure로 흡수하고 false")
    void retryStillFailsRecordsFailure() {
        CompensationTask t = failedTask();
        when(repository.findById(7L)).thenReturn(Optional.of(t));
        doThrow(new RuntimeException("여전히 PG 장애")).when(executor).attempt(7L);

        boolean ok = service.retry(7L);

        assertThat(ok).isFalse();
        verify(repository).saveAndFlush(t);
        verify(executor).attempt(7L);
        verify(executor).recordFailure(eq(7L), anyString());
    }

    @Test
    @DisplayName("retry: 없는 id는 false, 아무 것도 하지 않는다")
    void retryUnknownIdIsFalse() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        boolean ok = service.retry(99L);

        assertThat(ok).isFalse();
        verify(repository, never()).save(any());
        verify(executor, never()).attempt(anyLong());
        verify(executor, never()).recordFailure(anyLong(), anyString());
    }
}
