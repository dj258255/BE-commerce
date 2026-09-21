package com.beomsu.becommerce.personalization.internal;

import com.beomsu.becommerce.personalization.UserActivityEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 컨텍스트 저장소 — Redis {@code ctx:{userId}}.
 *
 * <p><b>읽기와 쓰기의 실패 규칙이 다르다. 이게 이 클래스의 핵심이다.</b>
 * <ul>
 *   <li>{@link #read} — <b>fail-open</b>. 저장소가 죽어도 온라인 경로는 살아야 한다. 빈 컨텍스트로
 *       폴백하고 지표를 센다. 예외를 올리면 개인화 저장소 장애가 상점 전체 장애가 된다
 *   <li>{@link #apply} — <b>예외를 전파</b>. 쓰기는 삼키면 이벤트가 조용히 사라진다
 * </ul>
 *
 * <h2>갱신은 원자적이다 — Lua 한 번으로 읽기·비교·쓰기를 끝낸다</h2>
 *
 * <p><b>왜 바꿨나</b>: 예전에는 Java에서 읽기 → seq 비교 → 쓰기를 했다. 그래서 <b>전송이 사용자별
 * 직렬성을 보장해야</b> 했고, Kafka 경로에서만 그 근거가 성립했다(라우팅 키가 {@code userId}라
 * 같은 파티션 → 같은 스레드). {@code IN_PROCESS}는 {@code @Async} 풀이라 같은 사용자의 이벤트가
 * 겹쳐 <b>한쪽 쓰기가 다른 쪽을 덮었다</b> — E2가 항목이 사라지는 것을 관측했다
 * (order-in-process 33.3% vs order-kafka 100%).
 * 근거: {@code personalization/docs/runs/20260921-e2-online-offline-일치율/report.md}, 이슈 #176.
 *
 * <p>이제 병합을 <b>Lua 안에서</b> 한다. 읽기와 쓰기 사이에 다른 스레드가 끼어들 틈이 없으므로
 * 전송 방식과 무관하게 항목이 유실되지 않는다. 비용은 스크립트 실행 한 번이고, Redis는 스크립트를
 * 단일 스레드로 실행하므로 경합이 사라진다.
 *
 * <p><b>순서 역전은 여전히 버린다.</b> 이건 결함이 아니라 설계다 — 컨텍스트는 "최근 것"을 담고,
 * 낮은 seq가 나중에 도착하면 그대로 두는 편이 되돌리는 것보다 낫다. 원자화가 고치는 것은
 * <b>동시 적용에서의 유실</b>이지 순서 역전이 아니다(E2의 `disorder` 조건은 두 전달 방식 모두 0%다).
 *
 * <p><b>직렬화</b>: 이 저장소는 커스텀 {@code RedisTemplate} 직렬화를 쓰지 않는다. 기존 Redis 기능이
 * 전부 {@code StringRedisTemplate} + 수동 인코딩이라 그 관례를 따른다.
 */
@Component
public class ContextStore {

    private static final Logger log = LoggerFactory.getLogger(ContextStore.class);

    /** 키 접두어 규약 — 기존 기능과 일관된다(`rl:` `queue:` `velocity:` `refresh:`). */
    private static final String PREFIX = "ctx:";

    /**
     * 읽기·병합·쓰기를 한 번에 한다. <b>도착 순서에 관대하다</b>(ADR-035) — 늦게 온 이벤트도
     * {@code seq} 위치에 끼워 넣고, 같은 {@code seq}가 이미 있으면 아무것도 하지 않는다(재배달 멱등).
     *
     * <p>결과는 "적용된 {@code seq} 집합에서 큰 것 {@code max-items}개"이므로 <b>도착 순서가 값을
     * 바꾸지 않는다.</b> 그래서 전송이 순서를 보장할 필요가 없다.
     *
     * <p>ARGV[1] = 항목 JSON({@code seq,itemId,activityType,occurredAt}), ARGV[2] = 적용 시각(ISO),
     * ARGV[3] = maxItems, ARGV[4] = TTL(ms). 반환은 반영된 컨텍스트의 JSON이다.
     */
    private static final String APPLY_LUA = """
            local key = KEYS[1]
            local incoming = cjson.decode(ARGV[1])
            local updatedAt = ARGV[2]
            local maxItems = tonumber(ARGV[3])
            local ttlMs = tonumber(ARGV[4])

            local raw = redis.call('GET', key)
            local items = {}
            local curSeq = 0
            if raw then
              local ctx = cjson.decode(raw)
              if ctx.seq then
                curSeq = tonumber(ctx.seq)
              end
              if ctx.items then
                items = ctx.items
              end
            end

            -- 재배달 멱등: 같은 seq 가 이미 있으면 아무것도 하지 않는다(쓰지도, TTL 을 건드리지도 않는다).
            for i = 1, #items do
              if tonumber(items[i].seq) == incoming.seq then
                return raw
              end
            end

            -- seq 내림차순 위치에 끼워 넣고 maxItems 로 자른다 — 낮은 seq 를 버리지 않는다.
            local merged = {}
            local i = 1
            local placed = false
            while #merged < maxItems do
              if not placed and (i > #items or incoming.seq > tonumber(items[i].seq)) then
                table.insert(merged, incoming)
                placed = true
              elseif i <= #items then
                table.insert(merged, items[i])
                i = i + 1
              else
                break
              end
            end

            local encoded = cjson.encode({
              seq = math.max(curSeq, incoming.seq),
              updatedAt = updatedAt,
              items = merged
            })
            redis.call('SET', key, encoded, 'PX', ttlMs)
            return encoded
            """;

    private static final RedisScript<String> APPLY_SCRIPT =
            new DefaultRedisScript<>(APPLY_LUA, String.class);

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
        this.maxItems = Math.max(maxItems, 1);
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
     * 이벤트를 컨텍스트에 반영한다. <b>원자적이다</b> — 읽기·비교·병합·쓰기가 Lua 한 번 안에서
     * 끝나므로, 같은 사용자의 이벤트가 동시에 들어와도 한쪽이 다른 쪽을 덮지 않는다.
     *
     * <p>순번이 낮거나 같으면 아무것도 하지 않고 현재 값을 그대로 돌려준다(재배달·순서 역전).
     */
    public OnlineContext apply(UserActivityEvent event) {
        String item = encode(new OnlineContext.Item(event.seq(), event.itemId(),
                event.activityType(), event.occurredAt()));

        String merged = redis.execute(APPLY_SCRIPT, List.of(key(event.userId())),
                item, Instant.now().toString(),
                String.valueOf(maxItems), String.valueOf(ttl.toMillis()));

        if (merged == null) {
            // 스크립트는 항상 값을 돌려준다 — null 이면 저장소가 응답하지 않은 것이다.
            throw new IllegalStateException("컨텍스트 적용 결과가 비었다. userId=" + event.userId());
        }
        return decode(merged);
    }

    private Optional<OnlineContext> raw(long userId) {
        String value = redis.opsForValue().get(key(userId));
        if (value == null) {
            // 키가 없다 = 아직 활동이 없거나 TTL이 만료됐다. 둘 다 "빈 컨텍스트"가 맞다.
            return Optional.empty();
        }
        return Optional.of(decode(value));
    }

    private OnlineContext decode(String json) {
        try {
            return objectMapper.readValue(json, OnlineContext.class);
        } catch (Exception e) {
            // 저장된 값이 우리 스키마와 다르다 = 스키마를 바꿨거나 값이 오염됐다.
            // 조용히 넘기면 영원히 빈 컨텍스트로 응답한다 — 최소한 흔적은 남긴다.
            throw new IllegalStateException("컨텍스트 값을 읽지 못했습니다.", e);
        }
    }

    private String encode(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("컨텍스트 값을 쓰지 못했습니다.", e);
        }
    }

    /**
     * 키에 <b>값 판을 붙인다</b>. 항목이 {@code seq}를 갖게 되면서 값 모양이 바뀌었고(ADR-035),
     * 옛 값에는 그 필드가 없어 제자리 병합이 성립하지 않는다. 컨텍스트는 TTL 7일의 캐시라
     * 마이그레이션하지 않고 새 키로 시작한다 — 옛 키는 만료로 사라진다.
     */
    String key(long userId) {
        return PREFIX + userId + ":v2";
    }
}
