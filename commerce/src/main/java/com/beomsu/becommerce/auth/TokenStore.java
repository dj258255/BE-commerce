package com.beomsu.becommerce.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 토큰 상태를 Redis에 보관한다 — 무상태 JWT가 다루지 못하는 두 가지 상태를 채운다.
 *
 * <ul>
 *   <li><b>refresh 토큰</b>(opaque UUID)을 저장/조회/삭제한다. refresh는 JWT가 아니라 Redis
 *       조회로만 검증되는 불투명 값이라, 서버가 언제든 무효화(회전·로그아웃)할 수 있다.
 *       key {@code refresh:{refreshId}} = {@code "{userId}|{rolesCsv}"}, TTL은 refresh 만료.</li>
 *   <li><b>access 폐기 목록(denylist)</b> — 로그아웃/탈취 시 access의 {@code jti}를
 *       {@code revoked:{jti}}로 올려, 만료 전이라도 per-request validator가 거부하게 한다.
 *       TTL은 그 토큰의 잔여 만료 시간(그 이후엔 어차피 서명 검증이 막으니 자동 정리).</li>
 * </ul>
 *
 * <p><b>폐기 검사는 fail-open</b>이다({@link #isRevoked}). Redis 장애 시 모든 요청을 401로
 * 떨구면 가용성이 무너지므로, 읽기 실패는 warn 로그를 남기고 통과시킨다(폐기는 예외적 이벤트,
 * 가용성 우선). 저장/삭제 경로는 감싸지 않는다 — 실패하면 상위에서 인지되는 편이 낫다.
 */
@Component
public class TokenStore {

    private static final Logger log = LoggerFactory.getLogger(TokenStore.class);

    private static final String REFRESH_PREFIX = "refresh:";
    private static final String REVOKED_PREFIX = "revoked:";

    private final StringRedisTemplate redis;

    public TokenStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void saveRefresh(String refreshId, String userId, String rolesCsv, Duration ttl) {
        redis.opsForValue().set(REFRESH_PREFIX + refreshId, userId + "|" + rolesCsv, ttl);
    }

    public Optional<RefreshData> lookupRefresh(String refreshId) {
        String value = redis.opsForValue().get(REFRESH_PREFIX + refreshId);
        if (value == null) {
            return Optional.empty();
        }
        int sep = value.indexOf('|');
        String userId = value.substring(0, sep);
        String rolesCsv = value.substring(sep + 1);
        List<String> roles = rolesCsv.isEmpty() ? List.of() : List.of(rolesCsv.split(","));
        return Optional.of(new RefreshData(userId, roles));
    }

    public void deleteRefresh(String refreshId) {
        redis.delete(REFRESH_PREFIX + refreshId);
    }

    public void revokeAccess(String jti, Duration ttl) {
        redis.opsForValue().set(REVOKED_PREFIX + jti, "1", ttl);
    }

    /**
     * 폐기 여부. Redis 예외 시 <b>fail-open</b>(false 반환 + warn) — 가용성 우선.
     */
    public boolean isRevoked(String jti) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(REVOKED_PREFIX + jti));
        } catch (RuntimeException e) {
            log.warn("Redis 폐기 조회 실패 — fail-open(통과)으로 처리합니다. jti={}, err={}",
                    jti, e.toString());
            return false;
        }
    }

    /** refresh 토큰에 묶인 소유자 식별자와 권한. */
    public record RefreshData(String userId, List<String> roles) {
    }
}
