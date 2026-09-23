package com.beomsu.becommerce.ratelimit;

import com.beomsu.becommerce.ratelimit.RateLimiter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RateLimiter 단위 테스트 — StringRedisTemplate 목. 고정 윈도우 INCR/EXPIRE 규약과
 * Redis 장애 시 fail-open을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class RateLimiterTest {

    private static final Duration WINDOW = Duration.ofSeconds(1);

    @Mock
    StringRedisTemplate redis;
    @Mock
    ValueOperations<String, String> valueOps;

    private RateLimiter limiter() {
        return new RateLimiter(redis);
    }

    @Test
    @DisplayName("limit 이내(count<=limit)면 true — 첫 INCR(count=1)에는 윈도우 TTL(EXPIRE)을 건다")
    void withinLimitAndExpireOnFirstIncrement() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(startsWith("rl:user:1:"))).thenReturn(1L);  // 윈도우 첫 요청

        boolean allowed = limiter().tryAcquire("user:1", 5, WINDOW);

        assertThat(allowed).isTrue();
        // 첫 요청이 키를 만들었으니 윈도우 길이만큼 TTL — 지난 윈도우 키가 Redis에 쌓이지 않는다.
        verify(redis).expire(startsWith("rl:user:1:"), org.mockito.ArgumentMatchers.eq(WINDOW));
    }

    @Test
    @DisplayName("limit 이내 후속 요청(count>1)은 true — EXPIRE는 다시 걸지 않는다")
    void withinLimitSubsequentNoExpire() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenReturn(5L);   // count == limit → 아직 허용

        boolean allowed = limiter().tryAcquire("user:1", 5, WINDOW);

        assertThat(allowed).isTrue();
        verify(redis, never()).expire(anyString(), org.mockito.ArgumentMatchers.any(Duration.class));
    }

    @Test
    @DisplayName("limit 초과(count>limit)면 false — 429로 쳐낼 근거")
    void overLimitRejected() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenReturn(6L);   // limit 5 초과

        assertThat(limiter().tryAcquire("user:1", 5, WINDOW)).isFalse();
    }

    @Test
    @DisplayName("Redis 예외 시 fail-open(true) — rate limiter 장애가 결제 중단으로 번지지 않게")
    void redisFailureFailOpen() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString()))
                .thenThrow(new RedisConnectionFailureException("redis down"));

        assertThat(limiter().tryAcquire("user:1", 5, WINDOW)).isTrue();
    }
}
