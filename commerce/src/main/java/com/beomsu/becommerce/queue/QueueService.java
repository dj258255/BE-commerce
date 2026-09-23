package com.beomsu.becommerce.queue;

import com.beomsu.becommerce.auth.TokenStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 선착순 대기열 — Redis Sorted Set(ZSET)으로 FIFO 줄을 세우고, 앞에서부터 admit-limit명만 입장시킨다.
 *
 * <p>키 규약:
 * <ul>
 *   <li>{@code queue:{eventId}} (ZSET) — {@code score=도착순번}, {@code member=userId}. 순번이 작을수록
 *       먼저 도착 → {@code ZRANK}(0-based 오름차순)가 곧 "내 앞의 인원"이다.</li>
 *   <li>{@code queue:{eventId}:seq} (INCR 카운터) — 도착 순번의 원천. 원자적 증가라 동시 진입에도
 *       순번이 겹치지 않는다.</li>
 *   <li>{@code admit:{eventId}:{userId}} (문자열, TTL) — <b>입장권(entry pass)</b>. admitted 판정 시
 *       발급되어 pass-ttl 동안 유효하다. 게이트 상품 주문 시 서버가 이 키의 존재를 검증한다.</li>
 * </ul>
 *
 * <p><b>입장 판정</b>: {@code rank < admitLimit}이면 입장. 예를 들어 admitLimit=100이면 rank 0~99(순번
 * 1~100번)만 입장이고, 나머지는 대기하며 폴링한다. 앞사람이 {@link #leave}로 빠지면 뒷사람 rank가
 * 자동으로 당겨져 순번이 앞으로 온다. admitted가 확인되는 순간(enter/status 모두) 입장권을 발급해,
 * 클라이언트 폴링 응답과 서버 측 강제 검증({@link #hasEntryPass})이 같은 사실을 보게 한다.
 *
 * <p><b>재진입 멱등</b>: 이미 큐에 있으면(ZSCORE 존재) 순번을 새로 뽑지 않고 기존 rank를 돌려준다 —
 * 폴링·새로고침으로 enter가 여러 번 와도 도착 순서가 뒤로 밀리지 않는다.
 *
 * <p><b>결제 경로와의 관계</b>: 기본은 독립 프리미티브(입장/상태/이탈)다. 단, <b>게이트 상품</b>
 * ({@code app.queue.gate.product-ids})으로 지정된 상품에 한해 order 모듈이 주문 생성 시 입장권을
 * 강제(권고→강제)한다 — 대상이 없으면(기본) 결제 경로는 대기열을 전혀 참조하지 않는다.
 *
 * <p><b>견고성</b>: 상태 조회({@link #status})는 Redis 장애 시 예외를 전파하지 않고 "대기열 없음"으로
 * fail-soft 처리한다(TokenStore의 fail-open과 같은 취지) — 순번 폴링이 500으로 터지지 않게 한다.
 * 입장권 검증({@link #hasEntryPass})도 Redis 장애 시 fail-open(통과)이다 — 대기열 인프라 장애가
 * 결제 전면 중단으로 번지지 않게 한다(가용성 우선).
 */
@Service
public class QueueService {

    private static final Logger log = LoggerFactory.getLogger(QueueService.class);

    private final StringRedisTemplate redis;
    private final int admitLimit;
    private final long passTtlSeconds;

    public QueueService(StringRedisTemplate redis,
                        @Value("${app.queue.admit-limit:100}") int admitLimit,
                        @Value("${app.queue.pass-ttl-seconds:600}") long passTtlSeconds) {
        this.redis = redis;
        this.admitLimit = admitLimit;
        this.passTtlSeconds = passTtlSeconds;
    }

    private static String queueKey(String eventId) {
        return "queue:" + eventId;
    }

    private static String seqKey(String eventId) {
        return "queue:" + eventId + ":seq";
    }

    private static String passKey(String eventId, String userId) {
        return "admit:" + eventId + ":" + userId;
    }

    /**
     * 대기열 입장(줄 서기). 이미 서 있으면 기존 순번을 그대로 돌려준다(멱등).
     */
    public QueuePosition enter(String eventId, String userId) {
        String key = queueKey(eventId);
        Double existing = redis.opsForZSet().score(key, userId);
        if (existing == null) {
            // 원자적 INCR로 도착 순번을 발급하고, 그 순번을 score로 ZADD → FIFO가 성립.
            Long score = redis.opsForValue().increment(seqKey(eventId));
            redis.opsForZSet().add(key, userId, score == null ? 0 : score);
        }
        return positionOf(eventId, key, userId);
    }

    /**
     * 현재 순번/입장 여부 조회. Redis 장애 시 예외 대신 "대기열 없음"을 돌려준다(fail-soft).
     */
    public QueuePosition status(String eventId, String userId) {
        try {
            return positionOf(eventId, queueKey(eventId), userId);
        } catch (RuntimeException e) {
            log.warn("대기열 상태 조회 실패 — 대기열 없음으로 처리합니다. eventId={}, userId={}, err={}",
                    eventId, userId, e.toString());
            return QueuePosition.notInQueue(eventId, 0);
        }
    }

    /**
     * 대기열 이탈(줄에서 빠짐). 입장해서 결제를 끝냈거나 스스로 떠날 때 호출 →
     * ZREM으로 제거되면 뒷사람들의 rank가 한 칸씩 당겨진다. 입장권도 함께 회수한다 —
     * 줄에서 빠진 사람이 남은 TTL로 게이트를 계속 통과하지 못하게.
     */
    public void leave(String eventId, String userId) {
        redis.opsForZSet().remove(queueKey(eventId), userId);
        redis.delete(passKey(eventId, userId));
    }

    /**
     * 입장권 보유 여부 — 게이트 상품 주문 시 order 모듈이 호출하는 서버 측 강제 검증.
     * Redis 장애 시 <b>fail-open</b>(true 반환 + warn) — 대기열 인프라 장애가 결제 전면 중단으로
     * 번지지 않게 한다(TokenStore.isRevoked와 같은 판단, 가용성 우선).
     */
    public boolean hasEntryPass(String eventId, String userId) {
        try {
            return redis.opsForValue().get(passKey(eventId, userId)) != null;
        } catch (RuntimeException e) {
            log.warn("입장권 조회 실패 — fail-open(통과)으로 처리합니다. eventId={}, userId={}, err={}",
                    eventId, userId, e.toString());
            return true;
        }
    }

    private QueuePosition positionOf(String eventId, String key, String userId) {
        Long rank = redis.opsForZSet().rank(key, userId);   // 0-based 오름차순(작은 score가 앞)
        Long card = redis.opsForZSet().zCard(key);
        long total = card == null ? 0 : card;
        if (rank == null) {
            return QueuePosition.notInQueue(eventId, total);
        }
        long waitingAhead = rank;               // 내 앞의 인원 = rank
        long position = rank + 1;               // 1-based 순번
        boolean admitted = rank < admitLimit;   // 앞에서부터 admitLimit명만 입장
        if (admitted) {
            // 입장 확인 순간(enter/status 모두) 입장권 발급 — TTL 동안 게이트 상품을 주문할 수 있다.
            // SET은 멱등이라 폴링마다 다시 와도 TTL만 연장될 뿐 문제 없다.
            redis.opsForValue().set(passKey(eventId, userId), "1", Duration.ofSeconds(passTtlSeconds));
        }
        return new QueuePosition(eventId, position, waitingAhead, admitted, total);
    }
}
