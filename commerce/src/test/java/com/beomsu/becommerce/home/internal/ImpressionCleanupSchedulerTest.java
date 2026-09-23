package com.beomsu.becommerce.home.internal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 노출 기록 보존의 <b>판단</b>을 고정한다 — #198③.
 *
 * <p>여기서 재는 것은 "지웠는가"가 아니라 <b>지우는 규칙</b>이다: 어떤 행이 대상인가(보존 기간) ·
 * 얼마씩 지우는가(배치) · 언제 멈추는가(회차 상한) · 빈 표에서 아무것도 안 하는가.
 */
class ImpressionCleanupSchedulerTest {

    private HomeImpressionRepository repository;

    @BeforeEach
    void setUp() {
        repository = mock(HomeImpressionRepository.class);
    }

    private ImpressionCleanupScheduler scheduler(long retentionDays, int batchSize, int maxBatches) {
        return new ImpressionCleanupScheduler(repository, retentionDays, batchSize, maxBatches);
    }

    private static HomeImpression row() {
        return mock(HomeImpression.class);
    }

    @Test
    @DisplayName("만료 행이 없으면 아무것도 지우지 않는다 — 빈 표에서 매시간 도는 것이 정상이다")
    void noExpiredRows() {
        when(repository.findByCreatedAtBeforeOrderByIdAsc(any(), any())).thenReturn(List.of());

        scheduler(7, 100, 20).run();

        verify(repository, never()).deleteAllInBatch(any());
    }

    @Test
    @DisplayName("배치보다 적게 나오면 그 회차를 끝낸다 — 다 지웠다는 뜻이므로 더 묻지 않는다")
    void stopsWhenBatchIsPartial() {
        when(repository.findByCreatedAtBeforeOrderByIdAsc(any(), any()))
                .thenReturn(List.of(row(), row()));

        scheduler(7, 100, 20).run();

        // 조회 1회 · 삭제 1회 — 부분 배치 뒤에 한 번 더 묻지 않는다.
        verify(repository, times(1)).findByCreatedAtBeforeOrderByIdAsc(any(), any());
        verify(repository, times(1)).deleteAllInBatch(any());
    }

    @Test
    @DisplayName("배치를 꽉 채워 나오면 이어서 지운다 — 하루 260만 행은 한 회차로 안 줄어든다")
    void keepsGoingWhileBatchesAreFull() {
        when(repository.findByCreatedAtBeforeOrderByIdAsc(any(), any()))
                .thenReturn(List.of(row(), row()), List.of(row()), List.of());

        scheduler(7, 2, 20).run();

        // 꽉 찬 배치(2) → 꽉 찬 배치(1, 부분이라 종료). 조회 2회 · 삭제 2회.
        verify(repository, times(2)).findByCreatedAtBeforeOrderByIdAsc(any(), any());
        verify(repository, times(2)).deleteAllInBatch(any());
    }

    @Test
    @DisplayName("한 회차의 배치 반복에 상한이 있다 — 정리가 한 번에 표를 다 비우지 않는다")
    void stopsAtMaxBatchesPerRun() {
        when(repository.findByCreatedAtBeforeOrderByIdAsc(any(), any()))
                .thenReturn(List.of(row(), row()));

        scheduler(7, 2, 3).run();

        verify(repository, times(3)).deleteAllInBatch(any());
    }

    @Test
    @DisplayName("자르는 시각이 보존 일수만큼 과거다 — 이 값이 곧 '무엇을 지우는가'다")
    void cutoffReflectsRetentionDays() {
        when(repository.findByCreatedAtBeforeOrderByIdAsc(any(), any())).thenReturn(List.of());
        Instant before = Instant.now();

        scheduler(7, 100, 20).run();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(repository).findByCreatedAtBeforeOrderByIdAsc(cutoff.capture(), any());
        // 관측 시각 기준으로 7일 전이어야 한다(위로는 실행 지연만큼의 여유만 둔다).
        assertThat(cutoff.getValue()).isBetween(before.minus(Duration.ofDays(7)).minusSeconds(5),
                before.minus(Duration.ofDays(7)).plusSeconds(5));
    }

    @Test
    @DisplayName("배치 크기로 가져온다 — 한 번에 전부 가져오면 배치의 의미가 없다")
    void asksForOneBatchOnly() {
        when(repository.findByCreatedAtBeforeOrderByIdAsc(any(), any())).thenReturn(List.of());

        scheduler(7, 1234, 20).run();

        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findByCreatedAtBeforeOrderByIdAsc(any(), page.capture());
        assertThat(page.getValue().getPageSize()).isEqualTo(1234);
    }
}
