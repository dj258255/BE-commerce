package com.beomsu.becommerce.personalization.internal;

import com.beomsu.becommerce.personalization.UserActivityEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 컨텍스트 저장소의 두 가지 실패 규칙을 고정한다 — <b>읽기는 fail-open, 쓰기는 전파</b>.
 * 이 구분이 E1-b가 재는 "결합"의 실체라 테스트로 못 박아 둔다.
 */
class ContextStoreTest {

    private static final long USER = 1L;
    private static final Duration TTL = Duration.ofDays(7);

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private SimpleMeterRegistry registry;
    private ObjectMapper objectMapper;
    private ContextStore store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);

        // 운영 ObjectMapper와 같은 설정으로 맞춘다(Instant는 ISO 문자열).
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        registry = new SimpleMeterRegistry();
        store = new ContextStore(redis, objectMapper, TTL, 20, new PersonalizationMetrics(registry));
    }

    private UserActivityEvent event(long seq) {
        return new UserActivityEvent(USER, 100L + seq, "CLICK", seq, Instant.now(), "SYNTHETIC");
    }

    private double failOpenCount() {
        return registry.get("personalization.context.fail_open").counter().count();
    }

    @Test
    @DisplayName("키가 없으면 빈 값 — 아직 활동이 없거나 TTL이 만료된 경우다")
    void missingKeyIsEmpty() {
        when(ops.get("ctx:1")).thenReturn(null);

        assertThat(store.read(USER)).isEmpty();
        assertThat(failOpenCount()).isZero();
    }

    @Test
    @DisplayName("저장소가 죽어도 읽기는 던지지 않는다 — 빈 값으로 폴백하고 지표를 센다")
    void readFailsOpen() {
        when(ops.get("ctx:1")).thenThrow(new RedisConnectionFailureException("연결 끊김"));

        assertThat(store.read(USER)).isEmpty();
        // 조용히 꺼지면 안 된다 — 폴백한 사실이 지표에 남아야 알림을 걸 수 있다.
        assertThat(failOpenCount()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("저장된 값이 우리 스키마와 다르면 읽기 실패로 폴백한다(영원히 빈 값으로 응답하지 않도록 흔적을 남긴다)")
    void corruptValueFallsBack() {
        when(ops.get("ctx:1")).thenReturn("{이건 JSON이 아니다");

        assertThat(store.read(USER)).isEmpty();
        assertThat(failOpenCount()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("처음 적용하면 컨텍스트를 만들고 TTL과 함께 쓴다")
    void firstApplyWritesWithTtl() {
        when(ops.get("ctx:1")).thenReturn(null);

        OnlineContext context = store.apply(event(1));

        assertThat(context.seq()).isEqualTo(1);
        assertThat(context.items()).hasSize(1);
        assertThat(context.items().get(0).itemId()).isEqualTo(101L);
        assertThat(context.updatedAt()).isNotNull();
        verify(ops).set(eq("ctx:1"), anyString(), eq(TTL));
    }

    @Test
    @DisplayName("순번이 같거나 낮으면 아무것도 쓰지 않는다 — 재배달·순서 역전이 같은 규칙으로 막힌다")
    void staleSeqIsIgnored() {
        OnlineContext current = new OnlineContext(5L, Instant.now(), java.util.List.of());
        when(ops.get("ctx:1")).thenReturn(encode(current));

        OnlineContext same = store.apply(event(5));
        OnlineContext older = store.apply(event(4));

        assertThat(same.seq()).isEqualTo(5);
        assertThat(older.seq()).isEqualTo(5);
        verify(ops, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("순번이 높으면 반영하고 최근 것부터 쌓는다")
    void newerSeqIsApplied() {
        when(ops.get("ctx:1")).thenReturn(encode(new OnlineContext(1L, Instant.now(),
                java.util.List.of(new OnlineContext.Item(101L, "CLICK", Instant.now())))));

        OnlineContext context = store.apply(event(2));

        assertThat(context.seq()).isEqualTo(2);
        assertThat(context.items()).hasSize(2);
        assertThat(context.items().get(0).itemId()).isEqualTo(102L);
        verify(ops).set(eq("ctx:1"), anyString(), eq(TTL));
    }

    @Test
    @DisplayName("max-items를 넘으면 오래된 것부터 버린다 — 컨텍스트가 무한히 자라지 않는다")
    void contextIsTrimmed() {
        ContextStore small = new ContextStore(redis, objectMapper, TTL, 2, new PersonalizationMetrics(registry));
        OnlineContext current = new OnlineContext(1L, Instant.now(), java.util.List.of(
                new OnlineContext.Item(1L, "CLICK", Instant.now()),
                new OnlineContext.Item(2L, "CLICK", Instant.now())));
        when(ops.get("ctx:1")).thenReturn(encode(current));

        OnlineContext context = small.apply(event(2));

        assertThat(context.items()).hasSize(2);
        assertThat(context.items().get(0).itemId()).isEqualTo(102L);
    }

    @Test
    @DisplayName("쓰기 실패는 전파한다 — 삼키면 이벤트가 조용히 사라진다")
    void writeFailurePropagates() {
        when(ops.get("ctx:1")).thenReturn(null);
        doThrow(new RedisConnectionFailureException("연결 끊김"))
                .when(ops).set(anyString(), anyString(), any(Duration.class));

        assertThatThrownBy(() -> store.apply(event(1)))
                .isInstanceOf(RedisConnectionFailureException.class);
    }

    private String encode(OnlineContext context) {
        try {
            return objectMapper.writeValueAsString(context);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("읽은 값은 쓴 값과 같다(JSON 왕복)")
    void roundTrip() {
        OnlineContext written = new OnlineContext(7L, Instant.parse("2026-09-20T10:00:00Z"),
                java.util.List.of(new OnlineContext.Item(42L, "VIEW", Instant.parse("2026-09-20T09:59:59Z"))));
        when(ops.get("ctx:1")).thenReturn(encode(written));

        Optional<OnlineContext> read = store.read(USER);

        assertThat(read).contains(written);
    }
}
