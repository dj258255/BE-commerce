package com.beomsu.becommerce.live;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

/**
 * 방송 특가 한정 수량 N의 Redis 선점(TTL) 게이트(R12, ADR-085) — commerce의 MySQL 재고와는
 * 완전히 별개인 "방송-상품" 프로모션 한도다. commerce 쪽 카탈로그 재고는 여전히 독립적으로
 * 또 지켜진다({@code CheckoutService.createSpecialPriceOrder}가 그대로
 * {@code StockReservationService}를 탄다) — 이 게이트가 어떤 이유로 뚫려도 실제 판매 가능
 * 수량이 바닥나는 것까지는 아니다(defense in depth).
 *
 * <p>ZSET {@code live:pin:{broadcastId}:holds}의 멤버 하나가 "확정을 시도했거나 성공한 주문
 * 하나"를 뜻한다. 멤버 키를 멱등 키 그대로 써서(R12.2) 같은 키로 재시도가 와도 슬롯을 두 번
 * 쓰지 않는다 — 이미 멤버면 TTL만 늘리고 성공으로 본다. score는 만료 시각(epoch ms)이라 매
 * 호출마다 지난 만료를 {@code ZREMRANGEBYSCORE}로 걷어내는 것 자체가 "미결제 반환"의 수동적
 * 형태다(R13 이전 단계에서도 이미 성립한다 — R13은 능동적 해제(결제 완료·취소 즉시 반환)를
 * 더할 뿐이고, 이 TTL 구조를 바꾸지 않는다. ADR-085 참고).
 *
 * <p><b>Redis 장애 시 fail-closed</b> — {@link com.beomsu.becommerce.fraud.velocity.RedisVelocityCounter}
 * (commerce)의 fail-open과 의도적으로 반대다. 거기는 "못 세면 통과시켜도 가용성이 이득"이지만,
 * 여기서 fail-open하면 한정 수량 N을 넘길 수 있어(R12의 핵심 불변식) 실패를 그대로 던져
 * 호출자가 주문을 거절하게 한다 — 정합성이 가용성보다 우선한다(ADR-085).
 */
@Component
public class LiveOrderGate {

    private static final RedisScript<Long> RESERVE = RedisScript.of("""
            local member = ARGV[1]
            local now = tonumber(ARGV[2])
            local ttlMs = tonumber(ARGV[3])
            local limit = tonumber(ARGV[4])
            local expireAt = now + ttlMs
            if redis.call('ZSCORE', KEYS[1], member) then
              redis.call('ZADD', KEYS[1], expireAt, member)
              redis.call('PEXPIRE', KEYS[1], ttlMs)
              return 1
            end
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now)
            if redis.call('ZCARD', KEYS[1]) >= limit then
              return 0
            end
            redis.call('ZADD', KEYS[1], expireAt, member)
            redis.call('PEXPIRE', KEYS[1], ttlMs)
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final Duration ttl;
    private final Clock clock;
    private final Counter rejected;

    @Autowired
    public LiveOrderGate(StringRedisTemplate redis, MeterRegistry meterRegistry,
            @Value("${app.live.order.hold-ttl:5m}") Duration ttl) {
        this(redis, meterRegistry, ttl, Clock.systemUTC());
    }

    LiveOrderGate(StringRedisTemplate redis, MeterRegistry meterRegistry, Duration ttl, Clock clock) {
        this.redis = redis;
        this.ttl = ttl;
        this.clock = clock;
        this.rejected = Counter.builder("live.order.gate.rejected.total")
                .description("한정 수량 Redis 선점 실패(매진)로 거절한 횟수")
                .register(meterRegistry);
    }

    /** 선점을 시도한다. 성공하면 {@code true}(= commerce 주문 생성으로 진행), 실패면 {@code false}
     * (= 결제 호출 없이 즉시 거절, R15 방향). */
    public boolean tryReserve(long broadcastId, int limit, String idempotencyKey) {
        Long result = redis.execute(RESERVE, List.of(holdsKey(broadcastId)),
                idempotencyKey, String.valueOf(clock.millis()), String.valueOf(ttl.toMillis()),
                String.valueOf(limit));
        boolean granted = result != null && result == 1L;
        if (!granted) {
            rejected.increment();
        }
        return granted;
    }

    /** 확정(commerce 주문 생성) 실패 시 선점을 즉시 돌려준다 — 같은 멱등 키 재시도가 다시 선점할 수 있게. */
    public void release(long broadcastId, String idempotencyKey) {
        redis.opsForZSet().remove(holdsKey(broadcastId), idempotencyKey);
    }

    private static String holdsKey(long broadcastId) {
        return "live:pin:" + broadcastId + ":holds";
    }
}
