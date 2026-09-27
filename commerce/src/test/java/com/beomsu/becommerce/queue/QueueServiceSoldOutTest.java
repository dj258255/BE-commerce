package com.beomsu.becommerce.queue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 매진 시 대기열 닫기(#385). 입장 차례가 온 사람에 한해 {@link QueueSoldOutGate}로 실제 매진을 묻고,
 * 매진이면 입장권을 주지 않은 채 {@code soldOut=true}로 알린다. 아직 입장 차례가 아닌 사람에게는
 * 묻지 않는다(비용을 입장 인원 규모로 묶는다).
 */
@ExtendWith(MockitoExtension.class)
class QueueServiceSoldOutTest {

    private static final String EVENT = "drop";
    private static final String QUEUE_KEY = "queue:drop";
    private static final String USER = "1";
    private static final int ADMIT_LIMIT = 10;

    @Mock
    StringRedisTemplate redis;
    @Mock
    ZSetOperations<String, String> zset;
    @Mock
    ValueOperations<String, String> valueOps;

    @Test
    @DisplayName("입장 차례(rank<limit)인데 매진이면: admitted=false, soldOut=true, 입장권 발급 안 함")
    void soldOutBlocksAdmission() {
        QueueSoldOutGate gate = mock(QueueSoldOutGate.class);
        when(gate.isSoldOut(EVENT)).thenReturn(true);
        QueueService service = new QueueService(redis, ADMIT_LIMIT, 600, 0, gate, java.time.Clock.systemUTC());
        when(redis.opsForZSet()).thenReturn(zset);
        when(zset.rank(QUEUE_KEY, USER)).thenReturn(0L);   // 맨 앞 — 입장 차례
        when(zset.zCard(QUEUE_KEY)).thenReturn(1L);

        QueuePosition pos = service.status(EVENT, USER);

        assertThat(pos.admitted()).isFalse();
        assertThat(pos.soldOut()).isTrue();
        verify(redis, never()).opsForValue();   // 입장권 SET 안 함
    }

    @Test
    @DisplayName("입장 차례인데 매진이 아니면: 기존과 동일하게 입장(admitted=true, soldOut=false)")
    void notSoldOutAdmitsNormally() {
        QueueSoldOutGate gate = mock(QueueSoldOutGate.class);
        when(gate.isSoldOut(EVENT)).thenReturn(false);
        QueueService service = new QueueService(redis, ADMIT_LIMIT, 600, 0, gate, java.time.Clock.systemUTC());
        when(redis.opsForZSet()).thenReturn(zset);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(zset.rank(QUEUE_KEY, USER)).thenReturn(0L);
        when(zset.zCard(QUEUE_KEY)).thenReturn(1L);

        QueuePosition pos = service.status(EVENT, USER);

        assertThat(pos.admitted()).isTrue();
        assertThat(pos.soldOut()).isFalse();
    }

    @Test
    @DisplayName("아직 입장 차례가 아니면(rank>=limit) 매진을 묻지 않는다 — 비용을 입장 인원 규모로 묶는다")
    void notYetAtFrontNeverAsksGate() {
        QueueSoldOutGate gate = mock(QueueSoldOutGate.class);
        QueueService service = new QueueService(redis, ADMIT_LIMIT, 600, 0, gate, java.time.Clock.systemUTC());
        when(redis.opsForZSet()).thenReturn(zset);
        when(zset.rank(QUEUE_KEY, USER)).thenReturn((long) ADMIT_LIMIT);   // 경계 — 아직 입장 아님
        when(zset.zCard(QUEUE_KEY)).thenReturn(500L);

        QueuePosition pos = service.status(EVENT, USER);

        assertThat(pos.admitted()).isFalse();
        assertThat(pos.soldOut()).isFalse();
        verify(gate, never()).isSoldOut(org.mockito.ArgumentMatchers.anyString());
    }
}
