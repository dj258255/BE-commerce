package com.beomsu.becommerce.fraud;

import com.beomsu.becommerce.testsupport.SharedContainers;
import com.beomsu.becommerce.fraud.internal.CardBlocklist;
import com.beomsu.becommerce.fraud.internal.FraudCheckRequest;
import com.beomsu.becommerce.fraud.internal.FraudService;
import com.beomsu.becommerce.fraud.velocity.RedisVelocityCounter;
import com.beomsu.becommerce.fraud.velocity.VelocityCounter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * <b>지연 예산</b> — FDS 판정을 승인 경로에 넣을 수 있는지를 숫자로 답한다.
 *
 * <p><b>왜 재나</b>: {@code docs/11} 이 "승인 경로 실시간 판정은 업계 표준인데 pay 가 안 하는 이유는
 * 난이도가 아니라 <b>지연 예산을 재본 적이 없어서</b>"라고 스스로 적어 뒀다. 재본 적 없는 것을
 * 근거로 설계를 정하고 있었던 셈이다.
 *
 * <p><b>무엇을 재나</b>: 판정 한 번에 걸리는 시간이다. 이 판정은 Redis velocity 카운터를
 * 카드·기기·IP 로 <b>세 번</b> 왕복한다 — 인메모리 목으로 재면 그 왕복이 사라져 답이 달라지므로
 * <b>실 Redis</b> 를 쓴다.
 *
 * <p><b>꼬리를 본다</b>: 평균이 아니라 p95·p99 다. 평균 40ms 인데 피크에 800ms 로 튀는 시스템은
 * 하필 부정거래가 몰릴 때 타임아웃 난다. 업계가 p99 로 예산을 잡는 이유다.
 *
 * <p>수치를 통과 조건으로 걸지 않는다 — 이 테스트는 <b>재는</b> 것이다. 기계마다 달라지는 값을
 * 임계로 걸면 CI 가 환경을 재는 꼴이 된다.
 */
@Tag("integration")
@DisplayName("FDS 지연 예산 — 승인 경로에 넣을 수 있는가")
class FdsLatencyBudgetTest {

    private static final int WARMUP = 200;
    private static final int SAMPLES = 2_000;

    static final GenericContainer<?> REDIS = SharedContainers.redis();   // 공용 Redis(#276)

    static {
        SharedContainers.flushRedis();   // 예전에는 클래스마다 새 Redis 였다 — 빈 상태로 시작한다
    }

    private FraudService serviceWith(VelocityCounter counter) {
        CardBlocklist blocklist = mock(CardBlocklist.class);
        when(blocklist.contains(anyString())).thenReturn(false);

        FraudService s = new FraudService(counter, blocklist);
        ReflectionTestUtils.setField(s, "velocityThreshold", 5);
        ReflectionTestUtils.setField(s, "velocityWeight", 40);
        ReflectionTestUtils.setField(s, "amountThreshold", 1_000_000L);
        ReflectionTestUtils.setField(s, "amountWeight", 30);
        ReflectionTestUtils.setField(s, "blacklistWeight", 100);
        ReflectionTestUtils.setField(s, "blockThreshold", 100);
        ReflectionTestUtils.setField(s, "reviewThreshold", 60);
        ReflectionTestUtils.setField(s, "challengeThreshold", 40);
        ReflectionTestUtils.setField(s, "deviceThreshold", 8);
        ReflectionTestUtils.setField(s, "deviceWeight", 25);
        ReflectionTestUtils.setField(s, "ipThreshold", 15);
        ReflectionTestUtils.setField(s, "ipWeight", 20);
        ReflectionTestUtils.setField(s, "longInstallmentMonths", 6);
        ReflectionTestUtils.setField(s, "installmentAmountThreshold", 1_000_000L);
        ReflectionTestUtils.setField(s, "installmentWeight", 20);
        return s;
    }

    private static long[] measure(FraudService service, int samples) {
        long[] ns = new long[samples];
        for (int i = 0; i < samples; i++) {
            // 키를 매번 바꾼다 — 같은 키만 두드리면 Redis 가 아니라 캐시 친화성을 재게 된다.
            FraudCheckRequest req = new FraudCheckRequest(
                    i, "card-" + i, "10.0." + (i % 250) + ".1", "dev-" + (i % 500), 30_000L, 0);
            long t0 = System.nanoTime();
            service.evaluate(req);
            ns[i] = System.nanoTime() - t0;
        }
        Arrays.sort(ns);
        return ns;
    }

    private static double ms(long[] sorted, double q) {
        return sorted[(int) Math.min(sorted.length - 1, Math.round(q * sorted.length))] / 1_000_000.0;
    }

    @Test
    @DisplayName("판정 한 번에 얼마나 걸리는지 p50·p95·p99로 잰다")
    void measureDecisionLatency() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        factory.afterPropertiesSet();
        StringRedisTemplate redis = new StringRedisTemplate(factory);

        FraudService service = serviceWith(new RedisVelocityCounter(redis, new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));

        measure(service, WARMUP);                       // JIT·커넥션 워밍업
        long[] sorted = measure(service, SAMPLES);

        System.out.printf("%n╔══ FDS 판정 지연 (실 Redis, %d회) ══%n", SAMPLES);
        System.out.printf("  p50 %.2f ms   p95 %.2f ms   p99 %.2f ms   max %.2f ms%n",
                ms(sorted, 0.50), ms(sorted, 0.95), ms(sorted, 0.99),
                sorted[sorted.length - 1] / 1_000_000.0);
        System.out.println("  (카드·기기·IP 세 키를 각각 Redis 로 왕복한다)");
        System.out.println("╚══════════════════════════════════════");

        assertThat(sorted).hasSize(SAMPLES);
    }
}
