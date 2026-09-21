package com.beomsu.becommerce.personalization.internal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 대기 정책의 계약을 고정한다 — E1의 비교군 다섯이 이 클래스 하나로 표현된다.
 *
 * <p>여기서 지키려는 것은 성능이 아니라 <b>정직함</b>이다. 상한을 넘겼으면 넘겼다고
 * ({@code reflected=false}) 돌려주고, 얼마를 기다렸는지({@code waitedMs})를 함께 준다.
 */
class OnlineContextReaderTest {

    private static final long USER = 1L;
    /** 폴링 1ms · 상한 50ms — 테스트가 빨리 끝나면서 루프가 실제로 돌 만큼만. */
    private static final long POLL_MS = 1L;
    private static final long MAX_WAIT_MS = 50L;

    private ContextStore store;
    private OnlineContextReader reader;

    @BeforeEach
    void setUp() {
        store = mock(ContextStore.class);
        reader = new OnlineContextReader(store, POLL_MS, MAX_WAIT_MS);
    }

    private Optional<OnlineContext> contextAt(long seq) {
        return Optional.of(new OnlineContext(seq, Instant.now(),
                List.of(new OnlineContext.Item(seq, seq, "CLICK", Instant.now())), Map.of("CLICK", 1L)));
    }

    @Test
    @DisplayName("expectSeq가 없으면 즉시 한 번만 읽는다 — 기다릴 이유가 없다")
    void noExpectationReadsOnce() {
        when(store.read(USER)).thenReturn(contextAt(3));

        ContextView view = reader.read(USER, null, 200L);

        assertThat(view.reflected()).isTrue();
        assertThat(view.seq()).isEqualTo(3);
        assertThat(view.source()).isEqualTo(ContextView.SOURCE_CONTEXT);
        verify(store, times(1)).read(USER);
    }

    @Test
    @DisplayName("이미 도달했으면 기다리지 않는다 — 상한이 0이어도 같은 답이 나온다")
    void alreadyReachedDoesNotWait() {
        when(store.read(USER)).thenReturn(contextAt(5));

        ContextView view = reader.read(USER, 5L, 0L);

        assertThat(view.reflected()).isTrue();
        assertThat(view.waitedMs()).isLessThan(MAX_WAIT_MS);
        verify(store, times(1)).read(USER);
    }

    @Test
    @DisplayName("도달하지 못하면 상한까지 기다리고, 못 받았다고 정직하게 돌려준다")
    void waitsUntilBudgetThenReportsNotReflected() {
        when(store.read(USER)).thenReturn(contextAt(1));

        ContextView view = reader.read(USER, 9L, MAX_WAIT_MS);

        assertThat(view.reflected()).isFalse();
        assertThat(view.seq()).isEqualTo(1);
        assertThat(view.waitedMs()).isGreaterThanOrEqualTo(MAX_WAIT_MS - 10);
        // 한 번만 읽고 포기하지 않았다 — 폴링이 실제로 돌았다(정확한 횟수는 타이밍에 달려 있다).
        verify(store, atLeast(5)).read(USER);
    }

    @Test
    @DisplayName("기다리는 사이 도달하면 조기 반환한다 — 상한을 다 쓰지 않는다")
    void returnsEarlyWhenReachedWhileWaiting() {
        when(store.read(USER))
                .thenReturn(contextAt(1), contextAt(1), contextAt(9));

        ContextView view = reader.read(USER, 9L, MAX_WAIT_MS);

        assertThat(view.reflected()).isTrue();
        assertThat(view.seq()).isEqualTo(9);
        assertThat(view.waitedMs()).isLessThan(MAX_WAIT_MS);
    }

    @Test
    @DisplayName("컨텍스트가 아예 없으면 EMPTY로 폴백한다 — 폴백을 숨기지 않는다")
    void emptyContextReportsEmptySource() {
        when(store.read(USER)).thenReturn(Optional.empty());

        ContextView view = reader.read(USER, 1L, 0L);

        assertThat(view.source()).isEqualTo(ContextView.SOURCE_EMPTY);
        assertThat(view.reflected()).isFalse();
        assertThat(view.itemCount()).isZero();
        assertThat(view.stalenessMs()).isNull();
    }

    @Test
    @DisplayName("waitMs는 상한으로 잘린다 — 무한 대기를 API로 열지 않는다")
    void waitIsClamped() {
        when(store.read(USER)).thenReturn(contextAt(1));

        // 10초를 요청해도 상한(50ms)에서 끊긴다.
        ContextView view = reader.read(USER, 9L, 10_000L);

        assertThat(view.reflected()).isFalse();
        assertThat(view.waitedMs()).isLessThan(MAX_WAIT_MS * 4);
    }

    @Test
    @DisplayName("stalenessMs는 컨텍스트가 마지막으로 갱신된 뒤 흐른 시간이다")
    void stalenessIsMeasuredFromUpdate() {
        OnlineContext tenSecondsOld = new OnlineContext(1L, Instant.now().minusSeconds(10),
                List.of(new OnlineContext.Item(1L, 1L, "CLICK", Instant.now())), Map.of("CLICK", 1L));
        when(store.read(USER)).thenReturn(Optional.of(tenSecondsOld));

        ContextView view = reader.read(USER, null, 0L);

        assertThat(view.stalenessMs()).isGreaterThanOrEqualTo(9_000L);
    }
}
