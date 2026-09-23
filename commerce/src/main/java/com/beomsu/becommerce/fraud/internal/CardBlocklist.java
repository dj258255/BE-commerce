package com.beomsu.becommerce.fraud.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 부정 카드 차단 목록 — <b>인스턴스 사이에 공유된다</b>.
 *
 * <p><b>왜 인메모리 Set으로는 부족한가</b>: 기동 시 DB(REJECTED 심사)에서 재적재하면 재시작은
 * 견딘다. 그런데 <b>런타임에 추가한 차단은 그 인스턴스에만 남는다.</b> A에서 카드를 차단해도
 * B·C는 모르고 계속 통과시킨다. 서버가 한 대일 때만 성립하던 방어다.
 *
 * <p>그래서 <b>Redis를 공유 목록으로</b> 쓴다. 진실 원천은 여전히 DB의 심사 이력이고,
 * Redis는 인스턴스들이 같은 답을 보게 하는 자리다. 로컬 Set은 <b>Redis가 죽었을 때만</b>
 * 쓰는 마지막 사본이다.
 *
 * <p><b>Redis 장애 시</b>: 로컬 사본으로 판정한다. velocity와 달리 fail-open하지 않는다 —
 * 차단은 "이미 부정으로 확정된 카드"라 통과시키는 쪽이 더 비싸다. 다만 로컬 사본은
 * 그 인스턴스가 아는 만큼만이라, 이 상태가 길어지면 경보가 필요하다.
 */
@Component
public class CardBlocklist {

    private static final Logger log = LoggerFactory.getLogger(CardBlocklist.class);

    private static final String KEY = "fds:card-blocklist";

    private final StringRedisTemplate redis;

    /** Redis가 죽었을 때 쓰는 마지막 사본. 진실 원천이 아니다. */
    private final Set<String> localFallback = ConcurrentHashMap.newKeySet();

    public CardBlocklist(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 차단 추가 — 공유 목록에 넣어 모든 인스턴스가 즉시 보게 한다. */
    public void block(String cardKey) {
        localFallback.add(cardKey);
        try {
            redis.opsForSet().add(KEY, cardKey);
        } catch (RuntimeException e) {
            log.warn("차단 목록 공유 실패 — 이 인스턴스에만 반영됩니다. cardKey={}, err={}",
                    cardKey, e.toString());
        }
    }

    public boolean contains(String cardKey) {
        try {
            return Boolean.TRUE.equals(redis.opsForSet().isMember(KEY, cardKey))
                    || localFallback.contains(cardKey);
        } catch (RuntimeException e) {
            log.warn("차단 목록 조회 실패 — 로컬 사본으로 판정합니다. err={}", e.toString());
            return localFallback.contains(cardKey);
        }
    }

    /** 기동 시 DB에서 재적재할 때 쓴다. */
    int size() {
        return localFallback.size();
    }
}
