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
     * <p><b>이 메서드는 원자적이지 않다.</b> 읽기 → 비교 → 쓰기이고 락이 없다. 그래서
     * <b>전송이 사용자별 직렬성을 보장해야</b> 한다:
     * <ul>
     *   <li>{@code KAFKA} — 라우팅 키가 {@code userId}라 한 사용자의 이벤트가 같은 파티션 → 같은
     *       스레드로 들어간다. 보장된다</li>
     *   <li>{@code IN_PROCESS} — {@code @ApplicationModuleListener}는 {@code @Async}라 <b>풀에 던진다.</b>
     *       같은 사용자의 두 이벤트가 동시에 들어와 한쪽 쓰기가 다른 쪽을 덮는다. E2가 실제로 항목
     *       하나가 사라지는 것을 관측했다(유실 16.7%) — 그래서 기본값이 KAFKA다</li>
     *   <li>{@code IN_REQUEST} — 트랜잭션 안이라 순서는 지켜지지만, 같은 사용자의 <b>동시 요청</b>은
     *       여전히 겹친다</li>
     * </ul>
     * <b>고치는 방법</b>: 이 갱신을 원자적으로(Lua CAS) 만들거나, 전송이 사용자별 직렬성을 보장하게
     * 한다. 앞의 것을 하면 {@code IN_PROCESS}가 다시 후보가 된다(아직 하지 않았다).
     * 근거: {@code personalization/docs/runs/20260921-e2-online-offline-일치율/report.md}.
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
