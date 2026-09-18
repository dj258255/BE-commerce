package com.beomsu.pay.payment.pg;

import com.beomsu.pay.payment.PaymentService;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
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
 * {@code payment.pg.max-concurrent-calls} 가 맡고, 기본값은 상한 없음이다(ADR-022).
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

    /** 상한 없이 감싼다. 테스트에서 장애 주입 더블을 감쌀 때 쓴다. */
    public ResilientPgClient(PgClient delegate) {
        this(delegate, 0);
    }

    /**
     * 활성 PG 어댑터를 위임 대상으로 감싼다. 개발/테스트는 {@code FakePgClient}, 운영은
     * {@code TossPgClient}가 {@code @Qualifier("pgDelegate")}로 주입된다(프로파일로 택일).
     */
    @Autowired
    public ResilientPgClient(@Qualifier("pgDelegate") PgClient delegate,
                             @Value("${payment.pg.max-concurrent-calls:40}") int maxConcurrentCalls) {
        this.delegate = delegate;
        // 0 이하면 상한을 걸지 않는다. 기본값 40 의 근거는 application.yml 과 ADR-022 에 있다.
        this.pgCallLimit = maxConcurrentCalls > 0 ? new Semaphore(maxConcurrentCalls) : null;

        CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(5)
                .minimumNumberOfCalls(3)
                .failureRateThreshold(50)                       // 절반 이상 실패하면 OPEN
                .waitDurationInOpenState(Duration.ofSeconds(5))
                .build();
        this.circuitBreaker = CircuitBreaker.of("pg", cbConfig);

        RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(3)
                // 지수 백오프 + 지터 — 재시도 폭풍(thundering herd) 방지
                .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(
                        Duration.ofMillis(20), 2.0, 0.5))
                .ignoreExceptions(CallNotPermittedException.class)  // 서킷 오픈이면 재시도 무의미
                .build();
        this.queryRetry = Retry.of("pg-query", retryConfig);
    }

    @Override
    public PgApproveResult approve(PgApproveCommand command) {
        // 상한에 걸리면 <b>확정 실패</b>로 돌린다. 미확정이 아니다.
        //
        // permit 을 못 얻으면 호출이 나가기 전에 끊기므로 요청이 PG 에 <b>닿지 않은 것이 보장된다.</b>
        // 그때 미확정으로 적으면 복구 배치가 조회할 대상 자체가 없는 유령 미확정이 생긴다.
        // 닿지 않았음이 보장될 때만 실패로 확정한다는 규칙은 ADR-020 의 failover 판정과 같다.
        if (pgCallLimit != null && !pgCallLimit.tryAcquire()) {
            log.warn("PG 동시 호출 상한 초과 — 승인 시도 없이 거절: {}", command.orderNo());
            return PgApproveResult.failed("PG 동시 호출 상한 초과");
        }
        try {
            return circuitBreaker.executeSupplier(() -> delegate.approve(command));
        } catch (CallNotPermittedException open) {
            log.warn("PG 서킷 오픈 — 승인 미확정 처리: {}", command.orderNo());
            return PgApproveResult.timeout("서킷 오픈: PG 장애로 승인 미확정");
        } catch (RuntimeException ex) {
            // 예외를 실패로 단정하지 않는다 — PG에서 처리됐을 수도 있다 → UNKNOWN
            log.warn("PG 승인 호출 예외 — 미확정 처리: {}", ex.getMessage());
            return PgApproveResult.timeout("PG 오류로 승인 미확정: " + ex.getMessage());
        } finally {
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
