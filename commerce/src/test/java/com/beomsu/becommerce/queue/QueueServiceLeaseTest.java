package com.beomsu.becommerce.queue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 입장 칸 만료(#383). 입장한 뒤 떠난 사람이 칸을 놓지 않아 대기열이 멈추던 것을, 만료가 지난 입장자의 칸과 입장권을
 * 회수해 푼다. 만료 시각을 고정 시계로 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class QueueServiceLeaseTest {

    private static final String EVENT = "drop";
    private static final String QUEUE_KEY = "queue:drop";
    private static final String ADMITTED_KEY = "queue:drop:admitted";
    private static final long LEASE = 30;
    private static final Instant NOW = Instant.parse("2026-09-27T12:00:00Z");

    @Mock
    StringRedisTemplate redis;
    @Mock
    ZSetOperations<String, String> zset;
    @Mock
    ValueOperations<String, String> valueOps;
    @Mock
    HashOperations<String, Object, Object> hash;

    private QueueService service() {
        return new QueueService(redis, 2, 600, LEASE, QueueSoldOutGate.NEVER, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("입장하면 처음 입장한 시각을 남긴다(폴링마다 늦추지 않도록 putIfAbsent)")
    void recordsAdmissionTime() {
        when(redis.opsForZSet()).thenReturn(zset);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(redis.<Object, Object>opsForHash()).thenReturn(hash);
        when(hash.entries(ADMITTED_KEY)).thenReturn(Map.of());
        when(zset.rank(QUEUE_KEY, "u1")).thenReturn(0L);
        when(zset.zCard(QUEUE_KEY)).thenReturn(1L);

        QueuePosition pos = service().status(EVENT, "u1");

        assertThat(pos.admitted()).isTrue();
        verify(hash).putIfAbsent(ADMITTED_KEY, "u1", String.valueOf(NOW.toEpochMilli()));
    }

    @Test
    @DisplayName("만료가 지난 입장자의 줄 자리 · 입장권을 회수하고, 만료 전 입장자는 그대로 둔다")
    void evictsOnlyExpiredAdmissions() {
        long expired = NOW.minusSeconds(LEASE + 1).toEpochMilli();
        long fresh = NOW.minusSeconds(LEASE - 1).toEpochMilli();
        when(redis.opsForZSet()).thenReturn(zset);
        when(redis.<Object, Object>opsForHash()).thenReturn(hash);
        when(hash.entries(ADMITTED_KEY)).thenReturn(Map.of("gone", String.valueOf(expired), "slow", String.valueOf(fresh)));
        when(zset.rank(QUEUE_KEY, "next")).thenReturn(null);   // 줄에 없는 사람이 물어도 회수는 돈다
        when(zset.zCard(QUEUE_KEY)).thenReturn(1L);

        service().status(EVENT, "next");

        verify(zset).remove(QUEUE_KEY, "gone");
        verify(hash).delete(ADMITTED_KEY, "gone");
        verify(redis).delete("admit:drop:gone");
        verify(zset, never()).remove(QUEUE_KEY, "slow");
        verify(redis, never()).delete("admit:drop:slow");
    }

    @Test
    @DisplayName("줄에서 나가면 입장 시각도 지운다")
    void leaveClearsAdmission() {
        when(redis.opsForZSet()).thenReturn(zset);
        when(redis.<Object, Object>opsForHash()).thenReturn(hash);

        service().leave(EVENT, "u1");

        verify(hash).delete(ADMITTED_KEY, "u1");
    }

    @Test
    @DisplayName("만료가 0 이면 해시를 건드리지 않는다(기존 동작)")
    void zeroLeaseDoesNotTouchHash() {
        QueueService noLease = new QueueService(redis, 2, 600, 0, QueueSoldOutGate.NEVER, Clock.fixed(NOW, ZoneOffset.UTC));
        when(redis.opsForZSet()).thenReturn(zset);
        when(zset.rank(QUEUE_KEY, "u1")).thenReturn(5L);
        when(zset.zCard(QUEUE_KEY)).thenReturn(6L);

        noLease.status(EVENT, "u1");

        verify(redis, never()).opsForHash();
        verify(valueOps, never()).set(anyString(), any(), any());
        verify(zset, never()).remove(eq(QUEUE_KEY), anyString());
    }
}
