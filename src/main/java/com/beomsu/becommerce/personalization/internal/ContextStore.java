package com.beomsu.becommerce.personalization.internal;

import com.beomsu.becommerce.personalization.UserActivityEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * 컨텍스트 저장소 — Redis {@code ctx:{userId}}.
 *
 * <p><b>읽기와 쓰기의 실패 규칙이 다르다. 이게 이 클래스의 핵심이다.</b>
 * <ul>
 *   <li>{@link #read} — <b>fail-open</b>. 저장소가 죽어도 온라인 경로는 살아야 한다. 빈 컨텍스트로
 *       폴백하고 지표를 센다. 예외를 올리면 개인화 저장소 장애가 상점 전체 장애가 된다
 *   <li>{@link #apply} — <b>예외를 전파</b>. 쓰기는 삼키면 이벤트가 조용히 사라진다. 호출한 쪽이
 *       판단하게 한다(전달 방식에 따라 재시도하거나, {@code IN_REQUEST}면 트랜잭션을 되돌린다)
 * </ul>
 * 이 구분이 E1-b가 재는 "결합"의 실체다 — {@code IN_REQUEST}는 쓰기 실패가 활동 기록까지 롤백시키고,
 * 나머지는 커밋 뒤라 활동 기록이 남는다.
 *
 * <p><b>직렬화</b>: 이 저장소는 커스텀 {@code RedisTemplate} 직렬화를 쓰지 않는다. 기존 Redis 기능이
 * 전부 {@code StringRedisTemplate} + 수동 인코딩({@code TokenStore}의 {@code "userId|rolesCsv"})이라
 * 그 관례를 따른다. JSON을 직접 쓴다.
 *
 * <p><b>TTL</b>이 있는 이유: 컨텍스트는 영원히 유효하지 않다. 오래된 컨텍스트는 인기 폴백보다 나쁠 수
 * 있고, E2의 불일치 원인에 "TTL 만료"가 들어 있다. 값은 설정으로 열어 둔다.
 */
@Component
public class ContextStore {

    private static final Logger log = LoggerFactory.getLogger(ContextStore.class);

    /** 키 접두어 규약 — 기존 기능과 일관된다(`rl:` `queue:` `velocity:` `refresh:`). */
    private static final String PREFIX = "ctx:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Duration ttl;
    private final int maxItems;
    private final PersonalizationMetrics metrics;

    public ContextStore(StringRedisTemplate redis,
                        ObjectMapper objectMapper,
                        @Value("${app.personalization.context.ttl:7d}") Duration ttl,
                        @Value("${app.personalization.context.max-items:20}") int maxItems,
                        PersonalizationMetrics metrics) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.ttl = ttl;
        this.maxItems = maxItems;
        this.metrics = metrics;
    }

    /** 온라인 읽기 — 실패하면 빈 값으로 폴백한다(예외를 올리지 않는다). */
    public Optional<OnlineContext> read(long userId) {
        try {
            return raw(userId);
        } catch (RuntimeException e) {
            metrics.contextFailOpen();
            log.warn("컨텍스트 읽기 실패 — 빈 컨텍스트로 폴백한다. userId={} cause={}", userId, e.toString());
            return Optional.empty();
        }
    }

    /**
     * 이벤트를 컨텍스트에 반영한다. <b>순번이 낮거나 같으면 아무것도 하지 않는다</b> — 재배달·순서
     * 역전을 같은 규칙으로 막는다. 바뀐 뒤의 컨텍스트(또는 그대로인 현재 값)를 돌려준다.
     *
     * <p>read-modify-write지만 분산 락이 없다. 라우팅 키가 {@code userId}라 한 사용자의 이벤트는
     * 항상 같은 파티션 → 같은 스레드가 처리하기 때문이다. 이 근거가 깨지는 순간(예: 파티션 키를
     * 바꾸는 순간) 락이 필요해진다.
     */
    public OnlineContext apply(UserActivityEvent event) {
        OnlineContext current = raw(event.userId()).orElse(OnlineContext.empty());
        if (!isNewer(event.seq(), current.seq())) {
            return current;
        }
        OnlineContext next = current.applied(event, maxItems);
        redis.opsForValue().set(key(event.userId()), encode(next), ttl);
        return next;
    }

    private boolean isNewer(long incoming, long current) {
        return incoming > current;
    }

    private Optional<OnlineContext> raw(long userId) {
        String value = redis.opsForValue().get(key(userId));
        if (value == null) {
            // 키가 없다 = 아직 활동이 없거나 TTL이 만료됐다. 둘 다 "빈 컨텍스트"가 맞다.
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(value, OnlineContext.class));
        } catch (Exception e) {
            // 저장된 값이 우리 스키마와 다르다 = 스키마를 바꿨거나 값이 오염됐다.
            // 조용히 넘기면 영원히 빈 컨텍스트로 응답한다 — 최소한 흔적은 남긴다.
            throw new IllegalStateException("컨텍스트 값을 읽지 못했습니다. userId=" + userId, e);
        }
    }

    private String encode(OnlineContext context) {
        try {
            return objectMapper.writeValueAsString(context);
        } catch (Exception e) {
            throw new IllegalStateException("컨텍스트 값을 쓰지 못했습니다.", e);
        }
    }

    private String key(long userId) {
        return PREFIX + userId;
    }
}
