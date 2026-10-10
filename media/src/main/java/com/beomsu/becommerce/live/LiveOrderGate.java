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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 방송 특가 한정 수량 N의 Redis 선점(TTL) 게이트(R12, ADR-085) — commerce의 MySQL 재고와는
 * 완전히 별개인 "방송-상품" 프로모션 한도다. commerce 쪽 카탈로그 재고는 여전히 독립적으로
 * 또 지켜진다({@code CheckoutService.createSpecialPriceOrder}가 그대로
 * {@code StockReservationService}를 탄다) — 이 게이트가 어떤 이유로 뚫려도 실제 판매 가능
 * 수량이 바닥나는 것까지는 아니다(defense in depth).
 *
 * <p>ZSET {@code live:pin:{broadcastId}:{generation}:holds}의 멤버 하나가 "확정을 시도했거나
 * 성공한 주문 하나"를 뜻한다. 멤버 키를 멱등 키 그대로 써서(R12.2) 같은 키로 재시도가 와도
 * 슬롯을 두 번 쓰지 않는다. {@code generation}(고정 세대, R12 재고정 수정)으로 같은 방송의
 * 다른 드롭과 Redis 키가 섞이지 않는다.
 *
 * <p><b>R13 이후: score는 "삭제 시각"이 아니라 "만료 평가 후보가 되는 시각"이다.</b> 예전에는
 * score가 지나면 {@code tryReserve}가 스스로 치웠다(ZREMRANGEBYSCORE). 그랬다면 결제 결과가
 * UNKNOWN인 홀드도 5분이 지나는 순간 조건 없이 사라져 R13.2("UNKNOWN인 동안은 유지")를
 * 어긴다. 그래서 자동 삭제를 없앴다 — score가 지난 멤버는 {@link #expiredHolds}로만
 * "평가 후보"가 되고, 실제로 지우거나(release) 영구화할지(confirmPermanently)는
 * {@code LiveOrderHoldReconciler}가 주문의 결제 상태를 본 뒤에만 정한다. 아무도 평가하지
 * 않으면(스케줄러가 꺼져 있으면) 멤버는 그대로 ZCARD에 남아 N을 계속 지킨다 — 과소판매
 * 방향의 안전장치다(가용성보다 정합성, ADR-085의 fail-closed 원칙과 같다).
 *
 * <p><b>Redis 장애 시 fail-closed</b> — {@link com.beomsu.becommerce.fraud.velocity.RedisVelocityCounter}
 * (commerce)의 fail-open과 의도적으로 반대다.
 */
@Component
public class LiveOrderGate {

    /** 결제가 확정(PAID)된 홀드의 score — 다시는 만료 평가 후보가 되지 않게 멀리 둔다(R13). */
    static final long PERMANENT_SCORE_MS = 9_999_999_999_999L; // 서기 2286년경

    // 주의: 이 키(ZSET)에 PEXPIRE를 걸지 않는다 — R13 전에는 ttlMs로 키 자체도 만료시켜
    // "아무도 안 쓰면 치워진다"를 노렸지만, R13부터는 멤버가 score(만료 후보 시각)를 지나도
    // LiveOrderHoldReconciler가 평가하기 전까지는 ZCARD에 그대로 남아야 한다(PAID는 영구히).
    // 키에 TTL을 걸면 한동안 새 주문이 없을 때 Redis가 키 전체(PAID로 영구화된 멤버까지)를
    // 통째로 지워 버린다 — 실측으로 걸렸다(이 TTL을 짧게 둔 테스트에서 즉시 재현). 키 크기는
    // 멤버 수가 한도(limit) 이하로 저절로 제한되므로 무한정 자라지 않는다.
    private static final RedisScript<Long> RESERVE = RedisScript.of("""
            local member = ARGV[1]
            local now = tonumber(ARGV[2])
            local ttlMs = tonumber(ARGV[3])
            local limit = tonumber(ARGV[4])
            local expireAt = now + ttlMs
            if redis.call('ZSCORE', KEYS[1], member) then
              redis.call('ZADD', KEYS[1], expireAt, member)
              return 1
            end
            if redis.call('ZCARD', KEYS[1]) >= limit then
              return 0
            end
            redis.call('ZADD', KEYS[1], expireAt, member)
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

    /**
     * 선점을 시도한다. 성공하면 {@code true}(= commerce 주문 생성으로 진행), 실패면 {@code false}
     * (= 결제 호출 없이 즉시 거절, R15 방향).
     */
    public boolean tryReserve(long broadcastId, long generation, int limit, String idempotencyKey) {
        Long result = redis.execute(RESERVE, List.of(holdsKey(broadcastId, generation)),
                idempotencyKey, String.valueOf(clock.millis()), String.valueOf(ttl.toMillis()),
                String.valueOf(limit));
        boolean granted = result != null && result == 1L;
        if (!granted) {
            rejected.increment();
        }
        return granted;
    }

    /** 확정(commerce 주문 생성) 실패 시 선점을 즉시 돌려준다 — 같은 멱등 키 재시도가 다시 선점할 수 있게. */
    public void release(long broadcastId, long generation, String idempotencyKey) {
        redis.opsForZSet().remove(holdsKey(broadcastId, generation), idempotencyKey);
        redis.opsForHash().delete(ordersKey(broadcastId, generation), idempotencyKey);
    }

    /** 결제 확정(PAID, R13) — 이 홀드를 영구화해 다시는 만료 평가 후보가 되지 않게 한다. */
    public void confirmPermanently(long broadcastId, long generation, String idempotencyKey) {
        redis.opsForZSet().add(holdsKey(broadcastId, generation), idempotencyKey, PERMANENT_SCORE_MS);
    }

    /** 이 홀드가 어느 주문인지 기록한다(R13의 반환·유지 판정이 주문의 결제 상태를 봐야 하므로). */
    public void recordOrder(long broadcastId, long generation, String idempotencyKey, String orderNo) {
        redis.opsForHash().put(ordersKey(broadcastId, generation), idempotencyKey, orderNo);
    }

    /** {@link #recordOrder}로 기록된 주문 번호. 없으면(기록 전 실패 등) {@code null}. */
    public String orderNoOf(long broadcastId, long generation, String idempotencyKey) {
        Object value = redis.opsForHash().get(ordersKey(broadcastId, generation), idempotencyKey);
        return value == null ? null : value.toString();
    }

    /** 지금 선점 중(확정 포함)인 수 — 남은 수량 = limit − currentCount(R13·R14). */
    public int currentCount(long broadcastId, long generation) {
        Long size = redis.opsForZSet().zCard(holdsKey(broadcastId, generation));
        return size == null ? 0 : size.intValue();
    }

    /**
     * score(만료 평가 후보가 되는 시각)가 {@code nowMs} 이전인 멤버들 — R13 반환·유지 판정의
     * 후보 목록이다. 영구화된(PAID) 멤버는 score가 멀어서 여기 안 걸린다.
     */
    public List<String> expiredHolds(long broadcastId, long generation, long nowMs) {
        Set<String> members = redis.opsForZSet()
                .rangeByScore(holdsKey(broadcastId, generation), Double.NEGATIVE_INFINITY, (double) nowMs);
        return members == null ? List.of() : new ArrayList<>(members);
    }

    private static String holdsKey(long broadcastId, long generation) {
        return "live:pin:" + broadcastId + ":" + generation + ":holds";
    }

    private static String ordersKey(long broadcastId, long generation) {
        return "live:pin:" + broadcastId + ":" + generation + ":orders";
    }
}
