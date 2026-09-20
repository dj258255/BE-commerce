package com.beomsu.becommerce.auth;

import com.beomsu.becommerce.auth.TokenStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TokenStore 단위 테스트 — StringRedisTemplate 목. Redis 실동작 없이 키 규약과
 * fail-open 동작을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class TokenStoreTest {

    @Mock
    StringRedisTemplate redis;
    @Mock
    ValueOperations<String, String> valueOps;

    private TokenStore store() {
        return new TokenStore(redis);
    }

    @Test
    @DisplayName("saveRefresh: refresh:{id} = userId|rolesCsv, TTL 지정")
    void saveRefresh() {
        when(redis.opsForValue()).thenReturn(valueOps);

        store().saveRefresh("rid", "1", "ROLE_USER", Duration.ofDays(14));

        verify(valueOps).set("refresh:rid", "1|ROLE_USER", Duration.ofDays(14));
    }

    @Test
    @DisplayName("lookupRefresh: 값이 있으면 userId/roles 파싱")
    void lookupRefreshFound() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("refresh:rid")).thenReturn("1|ROLE_USER,ROLE_ADMIN");

        Optional<TokenStore.RefreshData> data = store().lookupRefresh("rid");

        assertThat(data).isPresent();
        assertThat(data.get().userId()).isEqualTo("1");
        assertThat(data.get().roles()).containsExactly("ROLE_USER", "ROLE_ADMIN");
    }

    @Test
    @DisplayName("lookupRefresh: 값이 없으면 empty")
    void lookupRefreshMissing() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("refresh:gone")).thenReturn(null);

        assertThat(store().lookupRefresh("gone")).isEmpty();
    }

    @Test
    @DisplayName("isRevoked: 키가 있으면 true, 없으면 false")
    void isRevoked() {
        when(redis.hasKey("revoked:jti-1")).thenReturn(true);
        when(redis.hasKey("revoked:jti-2")).thenReturn(false);

        assertThat(store().isRevoked("jti-1")).isTrue();
        assertThat(store().isRevoked("jti-2")).isFalse();
    }

    @Test
    @DisplayName("isRevoked: Redis 예외 시 fail-open(false) — 가용성 우선")
    void isRevokedFailOpen() {
        when(redis.hasKey(anyString()))
                .thenThrow(new RedisConnectionFailureException("redis down"));

        assertThat(store().isRevoked("jti-1")).isFalse();   // 예외를 전파하지 않고 통과
    }
}
