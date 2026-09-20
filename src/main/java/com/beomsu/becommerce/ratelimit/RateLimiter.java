package com.beomsu.becommerce.ratelimit;

import com.beomsu.becommerce.auth.TokenStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Redis <b>고정 윈도우(fixed window)</b> 분산 rate limiter.
 *
 * <p>키 {@code rl:{key}:{epochWindow}}를 INCR하고, 윈도우 안의 카운트가 limit을 넘으면 거절한다.
 * epochWindow = 현재시각 / 윈도우길이라서 윈도우가 바뀌면 키가 바뀌고, 첫 INCR 때 EXPIRE를 걸어
 * 지난 윈도우 키는 자동 소멸한다. INCR가 원자적이라 다중 인스턴스에서도 카운트가 정확하다.
 *
 * <p><b>고정 윈도우의 한계(의도적 선택)</b>: 윈도우 경계 직전·직후에 몰아 치면 순간적으로 최대
 * 2×limit까지 통과할 수 있다. sliding window(ZSET)나 token bucket(Lua)으로 막을 수 있지만,
 * 여기서는 "폭주를 바깥 층에서 싸게 거절한다"가 목적이라 O(1) INCR 한 방의 단순성을 택했다 —
 * 경계 순간 2배는 뒤층(빠른 실패 풀·대기열)이 흡수한다.
 *
 * <p><b>Redis 예외 시 fail-open</b>(true 반환 + warn) — rate limiter가 죽었다고 결제를 전부
 * 막으면 가용성이 무너진다. {@link TokenStore#isRevoked}와 같은 판단(가용성 우선).
 */
@Component
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    private final StringRedisTemplate redis;

    public RateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 현재 윈도우에서 한 번의 허용량을 소비한다.
     *
     * @return 허용이면 true, 한도 초과면 false. Redis 장애 시 true(fail-open).
     */
    public boolean tryAcquire(String key, int limit, Duration window) {
        try {
            long epochWindow = System.currentTimeMillis() / window.toMillis();
            String redisKey = "rl:" + key + ":" + epochWindow;
            Long count = redis.opsForValue().increment(redisKey);
            if (count != null && count == 1L) {
                // 첫 요청이 윈도우 키를 만들었다 → 윈도우 길이만큼 TTL을 걸어 자동 정리.
                redis.expire(redisKey, window);
            }
            return count == null || count <= limit;
        } catch (RuntimeException e) {
            log.warn("rate limiter Redis 실패 — fail-open(통과)으로 처리합니다. key={}, err={}",
                    key, e.toString());
            return true;
        }
    }
}
