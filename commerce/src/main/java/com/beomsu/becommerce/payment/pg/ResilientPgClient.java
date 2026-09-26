package com.beomsu.becommerce.payment.pg;

import com.beomsu.becommerce.payment.PaymentService;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * PG 호출에 서킷브레이커·재시도를 입히는 데코레이터.
 *
 * <p>PG 장애가 우리 전체로 번지지 않게 한다. <b>다만 서킷만으로는 스레드 고갈을 막지 못한다</b> —
 * 서킷은 실패를 세므로 느리지만 성공하는 PG(브라운아웃)에서는 열리지 않는다. 그 구간의 자원 상한은
 * {@code payment.pg.max-concurrent-calls} 가 맡고, 운영 기본값은 40이다(ADR-022).
 *
 * <p>설계 원칙:
 * <ul>
 *   <li><b>승인(approve)은 재시도하지 않는다.</b> 멱등키 없이 승인을 재시도하면 이중결제가 난다.
 *       대신 실패/서킷 오픈 시 {@link PgApproveResult#timeout}(=UNKNOWN)으로 돌려, 복구 배치가
 *       나중에 조회로 확정하게 한다.</li>
 *   <li><b>조회(query)는 읽기라 재시도가 안전하다.</b> 지수 백오프 + 지터로 일시 장애를 흡수한다.</li>
 * </ul>
 * {@code @Primary}라 {@code PaymentService}는 이 구현을 주입받는다. 실제 PG 어댑터가 생기기 전까지
 * {@link FakePgClient}를 위임 대상으로 감싼다.
 */
@Component
@Primary
public class ResilientPgClient implements PgClient {

    private static final Logger log = LoggerFactory.getLogger(ResilientPgClient.class);

    private final PgClient delegate;
    private final CircuitBreaker circuitBreaker;
    private final Retry queryRetry;

    /**
     * PG 동시 호출 상한. {@code null} 이면 상한 없음(기본값)이다.
     *
     * <p><b>서킷브레이커로는 이 자리를 못 막는다.</b> 서킷은 <b>실패</b>를 센다. PG 가 응답 직전까지
     * 늦어지면서 성공하면 실패율은 0 이고 서킷은 열리지 않는다. 그동안 워커 스레드는 계속 묶인다.
     * 실측(ADR-022): PG 지연 3초에서 워커 100 개 중 86.6 개가 상시 점유됐고, 5초에서는 100 개를
     * 다 쓰고 처리량이 초당 30 건에서 17.6 건으로 내려앉았다. 같은 구간에서 DB 커넥션은 평균 10 개
     * 아래였다. 사가가 푼 것은 커넥션이고, 마르는 자리는 워커로 옮겨간 것이다.
     */
    private final Semaphore pgCallLimit;
    private final Counter pgConcurrencyRejected;
    private final Counter pgUnknownFallback;
    private final Counter pgQueryRetries;
    private final Counter pgQueryRetryExhausted;
    private final Timer pgApprovalLatency;

    /** 상한 없이 감싼다. 테스트에서 장애 주입 더블을 감쌀 때 쓴다. */
    public ResilientPgClient(PgClient delegate) {
        this(delegate, 0, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    /** 테스트용 호출 상한 생성자. 운영에서는 {@link MeterRegistry}를 주입받는 생성자를 사용한다. */
    public ResilientPgClient(PgClient delegate, int maxConcurrentCalls) {
        this(delegate, maxConcurrentCalls, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    /**
     * 활성 PG 어댑터를 위임 대상으로 감싼다. 개발/테스트는 {@code FakePgClient}, 운영은
     * {@code TossPgClient}가 {@code @Qualifier("pgDelegate")}로 주입된다(프로파일로 택일).
     */
    @Autowired
    public ResilientPgClient(@Qualifier("pgDelegate") PgClient delegate,
                             @Value("${payment.pg.max-concurrent-calls:40}") int maxConcurrentCalls,
                             @Value("${payment.pg.query-max-attempts:3}") int queryMaxAttempts,
                             ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this(delegate, maxConcurrentCalls, meterRegistryProvider.getIfAvailable(
                io.micrometer.core.instrument.simple.SimpleMeterRegistry::new), queryMaxAttempts);
    }

    /** 공통 초기화 경로. 테스트가 운영과 동일한 계측 구성을 주입할 때 사용한다. 조회는 최대 3회. */
    public ResilientPgClient(PgClient delegate, int maxConcurrentCalls, MeterRegistry meterRegistry) {
        this(delegate, maxConcurrentCalls, meterRegistry, 3);
    }

    /**
     * 조회 시도 횟수까지 정한다(#334). 1 이면 재시도하지 않는다. 1 보다 작으면 기본값 3 으로 돈다.
     */
    public ResilientPgClient(PgClient delegate, int maxConcurrentCalls, MeterRegistry meterRegistry, int queryMaxAttempts) {
        this.delegate = delegate;
        // 0 이하면 상한을 걸지 않는다. 운영 기본값 40의 근거는 application.yml과 ADR-022에 있다.
        this.pgCallLimit = maxConcurrentCalls > 0 ? new Semaphore(maxConcurrentCalls) : null;
        this.pgConcurrencyRejected = Counter.builder("payment.pg.approval.rejected")
                .tag("reason", "concurrency_limit")
                .description("PG 동시 호출 상한으로 승인 전에 거절된 요청 수")
                .register(meterRegistry);
        this.pgUnknownFallback = Counter.builder("payment.pg.approval.unknown")
                .description("PG 승인 결과를 알 수 없어 UNKNOWN으로 보존한 요청 수")
                .register(meterRegistry);
        this.pgQueryRetries = Counter.builder("payment.pg.query.retry")
                .description("PG 상태 조회에서 실제로 실행된 추가 재시도 횟수")
                .register(meterRegistry);
        this.pgQueryRetryExhausted = Counter.builder("payment.pg.query.retry.exhausted")
                .description("PG 상태 조회가 재시도 예산을 모두 소진한 횟수")
                .register(meterRegistry);
        this.pgApprovalLatency = Timer.builder("payment.pg.approval.latency")
                .description("PG 승인 호출 시간")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);

        CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(5)
                .minimumNumberOfCalls(3)
                .failureRateThreshold(50)                       // 절반 이상 실패하면 OPEN
                .waitDurationInOpenState(Duration.ofSeconds(5))
                .build();
        this.circuitBreaker = CircuitBreaker.of("pg", cbConfig);

        RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(queryMaxAttempts >= 1 ? queryMaxAttempts : 3)
                // 지수 백오프 + 지터 — 재시도 폭풍(thundering herd) 방지
                .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(
                        Duration.ofMillis(20), 2.0, 0.5))
                .ignoreExceptions(CallNotPermittedException.class)  // 서킷 오픈이면 재시도 무의미
                .build();
        this.queryRetry = Retry.of("pg-query", retryConfig);
        this.queryRetry.getEventPublisher()
                .onRetry(event -> pgQueryRetries.increment())
                .onError(event -> pgQueryRetryExhausted.increment());
    }

    @Override
    public PgApproveResult approve(PgApproveCommand command) {
        Timer.Sample timer = Timer.start();
        // 상한에 걸리면 <b>확정 실패</b>로 돌린다. 미확정이 아니다.
        //
        // permit 을 못 얻으면 호출이 나가기 전에 끊기므로 요청이 PG 에 <b>닿지 않은 것이 보장된다.</b>
        // 그때 미확정으로 적으면 복구 배치가 조회할 대상 자체가 없는 유령 미확정이 생긴다.
        // 닿지 않았음이 보장될 때만 실패로 확정한다는 규칙은 ADR-020 의 failover 판정과 같다.
        if (pgCallLimit != null && !pgCallLimit.tryAcquire()) {
            pgConcurrencyRejected.increment();
            log.warn("PG 동시 호출 상한 초과 — 승인 시도 없이 거절: {}", command.orderNo());
            PgApproveResult result = PgApproveResult.failed("PG 동시 호출 상한 초과");
            timer.stop(pgApprovalLatency);
            return result;
        }
        try {
            PgApproveResult result = circuitBreaker.executeSupplier(() -> delegate.approve(command));
            if (result.outcome() == PgOutcome.TIMEOUT) {
                pgUnknownFallback.increment();
            }
            return result;
        } catch (CallNotPermittedException open) {
            pgUnknownFallback.increment();
            log.warn("PG 서킷 오픈 — 승인 미확정 처리: {}", command.orderNo());
            return PgApproveResult.timeout("서킷 오픈: PG 장애로 승인 미확정");
        } catch (RuntimeException ex) {
            pgUnknownFallback.increment();
            // 예외를 실패로 단정하지 않는다 — PG에서 처리됐을 수도 있다 → UNKNOWN
            log.warn("PG 승인 호출 예외 — 미확정 처리: {}", ex.getMessage());
            return PgApproveResult.timeout("PG 오류로 승인 미확정: " + ex.getMessage());
        } finally {
            timer.stop(pgApprovalLatency);
            if (pgCallLimit != null) {
                pgCallLimit.release();
            }
        }
    }

    @Override
    public PgCancelResult cancel(PgCancelCommand command) {
        // 취소도 서킷으로 보호하되 재시도는 하지 않는다(호출부가 실패를 처리).
        return circuitBreaker.executeSupplier(() -> delegate.cancel(command));
    }

    @Override
    public PgQueryResult query(String paymentKey) {
        Supplier<PgQueryResult> guarded = Retry.decorateSupplier(queryRetry,
                () -> circuitBreaker.executeSupplier(() -> delegate.query(paymentKey)));
        // 조회 실패는 예외로 전파 — 복구 배치가 건별로 잡아 다음 주기에 다시 시도한다.
        return guarded.get();
    }

    CircuitBreaker circuitBreaker() {
        return circuitBreaker;
    }
}
