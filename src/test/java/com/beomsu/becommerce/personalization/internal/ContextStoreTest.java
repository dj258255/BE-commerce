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
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 컨텍스트 저장소의 두 가지 실패 규칙을 고정한다 — <b>읽기는 fail-open, 쓰기는 전파</b>.
 *
 * <p>병합 자체는 <b>Lua 안에</b> 있으므로 여기서 검증하지 않는다(모킹으로는 Lua를 검증할 수 없다).
 * 이 클래스는 <b>앱 쪽 배선</b>만 본다 — 스크립트를 부르는가, 돌려받은 JSON을 읽는가, 실패를
 * 어떻게 다루는가. 병합·원자성은 {@code ContextStoreConcurrencyTest}가 실 Redis로 본다.
 */
class ContextStoreTest {

    private static final long USER = 1L;

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
        store = new ContextStore(redis, objectMapper, Duration.ofDays(7), 20, new PersonalizationMetrics(registry));
    }

    private UserActivityEvent event(long seq) {
        return new UserActivityEvent(USER, 100L + seq, "CLICK", seq, Instant.now(), "SYNTHETIC");
    }

    private String encode(OnlineContext context) {
        try {
            return objectMapper.writeValueAsString(context);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private double failOpenCount() {
        return registry.get("personalization.context.fail_open").counter().count();
    }

    @Test
    @DisplayName("키가 없으면 빈 값 — 아직 활동이 없거나 TTL이 만료된 경우다")
    void missingKeyIsEmpty() {
        when(ops.get(store.key(USER))).thenReturn(null);

        assertThat(store.read(USER)).isEmpty();
        assertThat(failOpenCount()).isZero();
    }

    @Test
    @DisplayName("저장소가 죽어도 읽기는 던지지 않는다 — 빈 값으로 폴백하고 지표를 센다")
    void readFailsOpen() {
        when(ops.get(store.key(USER))).thenThrow(new RedisConnectionFailureException("연결 끊김"));

        assertThat(store.read(USER)).isEmpty();
        // 조용히 꺼지면 안 된다 — 폴백한 사실이 지표에 남아야 알림을 걸 수 있다.
        assertThat(failOpenCount()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("저장된 값이 우리 스키마와 다르면 읽기 실패로 폴백한다(영원히 빈 값으로 응답하지 않도록)")
    void corruptValueFallsBack() {
        when(ops.get(store.key(USER))).thenReturn("{이건 JSON이 아니다");

        assertThat(store.read(USER)).isEmpty();
        assertThat(failOpenCount()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("읽은 값은 쓴 값과 같다(JSON 왕복)")
    void roundTrip() {
        OnlineContext written = new OnlineContext(7L, Instant.parse("2026-09-21T10:00:00Z"),
                java.util.List.of(new OnlineContext.Item(7L, 42L, "VIEW", Instant.parse("2026-09-21T09:59:59Z"))));
        when(ops.get(store.key(USER))).thenReturn(encode(written));

        assertThat(store.read(USER)).contains(written);
    }

    @Test
    @DisplayName("적용은 Lua 스크립트 하나를 부르고, 돌려받은 컨텍스트를 그대로 읽는다")
    void applyUsesScriptAndParsesResult() {
        OnlineContext merged = new OnlineContext(3L, Instant.now(),
                java.util.List.of(new OnlineContext.Item(3L, 103L, "CLICK", Instant.now())));
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(encode(merged));

        OnlineContext result = store.apply(event(3));

        assertThat(result.seq()).isEqualTo(3L);
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).itemId()).isEqualTo(103L);
    }

    @Test
    @DisplayName("스크립트가 값을 못 돌려주면 던진다 — 조용히 넘기면 이벤트가 사라진다")
    void applyRejectsNullResult() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(null);

        assertThatThrownBy(() -> store.apply(event(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("컨텍스트 적용 결과가 비었다");
    }

    @Test
    @DisplayName("쓰기 실패는 전파한다 — 삼키면 이벤트가 조용히 사라진다")
    void applyPropagatesFailure() {
        doThrow(new RedisConnectionFailureException("연결 끊김"))
                .when(redis).execute(any(RedisScript.class), anyList(), any(Object[].class));

        assertThatThrownBy(() -> store.apply(event(1)))
                .isInstanceOf(RedisConnectionFailureException.class);
    }
}
