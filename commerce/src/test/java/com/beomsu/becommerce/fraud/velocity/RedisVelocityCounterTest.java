package com.beomsu.becommerce.fraud.velocity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 고정 윈도우 카운터 규약과 Redis 장애 시 fail-open을 검증한다.
 *
 * <p>INCR과 EXPIRE를 나눠 부르면 그 사이에 죽었을 때 <b>TTL 없는 키</b>가 영구히 남는다.
 * 카드마다 분마다 키가 생기므로 Redis가 계속 커진다. 그래서 한 스크립트로 실행한다.
 */
@ExtendWith(MockitoExtension.class)
class RedisVelocityCounterTest {

    @Mock
    StringRedisTemplate redis;

    private RedisVelocityCounter counter() {
        return new RedisVelocityCounter(redis, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    @Test
    @DisplayName("INCR과 EXPIRE를 한 스크립트로 실행한다 — 둘 사이에 죽어도 TTL 없는 키가 안 남는다")
    void incrementAndExpireAreAtomic() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);

        int count = counter().recordAndCount("card:c1");

        assertThat(count).isEqualTo(1);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Object> args = ArgumentCaptor.forClass(Object.class);
        verify(redis).execute(any(RedisScript.class), keys.capture(), args.capture());

        assertThat(keys.getValue()).singleElement()
                .asString().startsWith("velocity:card:c1:");
        assertThat(args.getValue())
                .as("TTL은 윈도우 길이(초)로 넘긴다")
                .isEqualTo("60");

        verify(redis, never()).expire(anyString(), any());
    }

    @Test
    @DisplayName("후속 호출은 스크립트가 돌려준 값을 그대로 쓴다")
    void subsequentHitReturnsCount() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(4L);

        assertThat(counter().recordAndCount("card:c1")).isEqualTo(4);
    }

    @Test
    @DisplayName("Redis 예외 시 fail-open — 이번 시도만 센 것으로 보고 1을 반환한다")
    void redisFailureFailOpen() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("redis down"));

        assertThat(counter().recordAndCount("card:c1")).isEqualTo(1);
    }
}
