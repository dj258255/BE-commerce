package com.beomsu.becommerce.fraud.velocity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.List;
import java.time.Duration;
import java.time.Instant;

/**
 * Redis <b>고정 윈도우(fixed window)</b> velocity 카운터 — 다중 인스턴스에서 정확하다.
 *
 * <p>{@link InMemoryVelocityCounter}는 프로세스 로컬이라 인스턴스가 여러 대면 카운트가 갈라져
 * velocity 룰이 무력해진다. 여기서는 공유 Redis에 키 {@code velocity:{key}:{epochMinute}}를 INCR하고,
 * 첫 INCR(=1)일 때만 60초 TTL을 걸어 지난 윈도우 키가 자동 소멸하게 한다. INCR가 원자적이라
 * 다중 인스턴스에서도 카운트가 정확하다({@link com.beomsu.becommerce.RateLimiter}와 같은 패턴).
 *
 * <p><b>고정 윈도우의 한계</b>: epochMinute 경계 직전·직후에 몰아 치면 순간적으로 최대 2×윈도우까지
 * 통과할 수 있다. velocity는 "이상 급증 신호"용이라 이 정도 오차는 수용한다.
 *
 * <p><b>Redis 예외 시 fail-open</b> — 이번 시도만 센 것으로 보고 {@code 1}을 반환한다(velocity 룰
 * 통과) + warn. velocity 카운터가 죽었다고 결제를 막으면 가용성이 무너지므로 가용성을 우선한다
 * ({@link com.beomsu.becommerce.TokenStore#isRevoked}와 같은 판단).
 *
 * <p>{@code app.fds.velocity.redis=false} 면 이 빈이 비활성화되어 {@link InMemoryVelocityCounter}로
 * 폴백한다(기본 활성). @Primary 라 둘 다 있을 때 이 빈이 주입된다.
 */
@Component
@Primary
@ConditionalOnProperty(name = "app.fds.velocity.redis", havingValue = "true", matchIfMissing = true)
public class RedisVelocityCounter implements VelocityCounter {

    private static final Logger log = LoggerFactory.getLogger(RedisVelocityCounter.class);

    private static final Duration WINDOW = Duration.ofSeconds(60);

    /** INCR 하고, 그 호출이 키를 만들었으면 같은 원자 단위에서 TTL 을 건다. */
    private static final RedisScript<Long> INCR_WITH_TTL = RedisScript.of("""
            local n = redis.call('INCR', KEYS[1])
            if n == 1 then
              redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return n
            """, Long.class);

    private final StringRedisTemplate redis;

    /**
     * Redis 가 죽어 속도 제한이 무력화된 횟수.
     *
     * <p>fail-open 은 <b>결제를 세우지 않는 대신 규칙 하나가 조용히 꺼진다.</b> 로그만 남기면
     * 아무도 안 본다. 이상거래가 몰리는 때와 Redis 가 흔들리는 때가 겹치면
     * <b>가장 필요한 순간에 규칙이 없는 상태</b>가 되는데, 그것을 알 방법이 없었다.
     */
    private final Counter failOpen;

    public RedisVelocityCounter(StringRedisTemplate redis, MeterRegistry meterRegistry) {
        this.redis = redis;
        this.failOpen = Counter.builder("fraud.velocity.fail.open.total")
                .description("Redis 실패로 속도 제한을 건너뛴 횟수. 0이 아니면 그 시간대의 판정은 규칙 하나가 빠진 값이다")
                .register(meterRegistry);
    }

    @Override
    public int recordAndCount(String key) {
        try {
            long epochMinute = Instant.now().getEpochSecond() / WINDOW.getSeconds();
            String redisKey = "velocity:" + key + ":" + epochMinute;
            // INCR 과 EXPIRE 를 나눠 부르면 그 사이에 죽었을 때 <TTL 없는 키>가 영구히 남는다.
            // 카드마다 분마다 키가 생기므로 그런 키가 쌓이면 Redis 가 계속 커진다. 한 번에 실행한다.
            Long count = redis.execute(INCR_WITH_TTL, List.of(redisKey),
                    String.valueOf(WINDOW.getSeconds()));
            return count == null ? 1 : count.intValue();
        } catch (RuntimeException e) {
            failOpen.increment();
            log.warn("velocity Redis 실패 — fail-open(이번 시도만 카운트)으로 처리합니다. key={}, err={}",
                    key, e.toString());
            return 1;
        }
    }
}
