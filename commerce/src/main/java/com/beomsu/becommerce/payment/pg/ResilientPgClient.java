package com.beomsu.becommerce.payment.pg;

import com.beomsu.becommerce.payment.PaymentService;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
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
import java.util.concurrent.atomic.AtomicInteger;
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
 *   <li><b>승인(approve)이 타임아웃이면 같은 멱등키로 최대 1회(설정값) 재전송한다.</b>
 *       {@code TossPgClient}가 승인 요청에 주문번호를 {@code Idempotency-Key}로 싣고 있어(토스
 *       문서: 타임아웃 응답 미수신 시 동일 키 재전송이 안전하다) 재전송해도 이중결제가 나지 않는다.
 *       재전송도 동시 호출 상한(ADR-022)과 이 승인 서킷을 그대로 거친다 — 상한을 못 채우거나
 *       서킷이 열려 있으면 재전송하지 않고 원래 {@link PgApproveResult#timeout}(=UNKNOWN)을 그대로
 *       돌려, 복구 배치가 나중에 조회로 확정하게 한다. 재전송도 타임아웃이거나 PG가 "처리 중"(토스
 *       409 {@code IDEMPOTENT_REQUEST_PROCESSING})으로 답해도 같은 UNKNOWN 경로로 넘긴다.
 *       명시적 거절(4xx 확정 실패)은 재전송하지 않는다 — 다시 보내도 같은 답이 온다.</li>
 *   <li><b>조회(query)는 읽기라 재시도가 안전하다.</b> 지수 백오프 + 지터로 일시 장애를 흡수한다.</li>
 *   <li><b>서킷은 승인 · 취소 · 조회가 따로 쓴다(#372).</b> 하나를 같이 쓰면 조회만 실패해도 서킷이 열려
 *       새 승인이 PG 에 가지 못했다(ADR-057 대가). 조회 서킷은 창을 넓혀 무작위 실패로는 거의 열리지 않게 한다.</li>
 *   <li><b>서킷이 열려 보내지 않은 승인은 확정 실패다(#372).</b> PG 에 닿지 않은 것이 보장되므로 동시 호출
 *       상한과 같은 규칙을 따른다. 미확정으로 적으면 조회할 대상이 없는 유령 미확정이 생긴다.</li>
 * </ul>
 * {@code @Primary}라 {@code PaymentService}는 이 구현을 주입받는다. 실제 PG 어댑터가 생기기 전까지
 * {@link FakePgClient}를 위임 대상으로 감싼다.
 */
@Component
@Primary
public class ResilientPgClient implements PgClient {

    private static final Logger log = LoggerFactory.getLogger(ResilientPgClient.class);

    private final PgClient delegate;
    private final CircuitBreaker approveCircuit;
    private final CircuitBreaker cancelCircuit;
    private final CircuitBreaker queryCircuit;
    private final Retry queryRetry;

    /** 승인 타임아웃 시 같은 멱등키로 다시 보낼 최대 횟수. 0이면 재전송하지 않는다(이전 동작). */
    private final int approveResendMaxAttempts;
    private final Counter pgApprovalResent;
    private final Counter pgApprovalResendSucceeded;

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
    private final AtomicInteger pgApprovalsInFlight = new AtomicInteger();
    private final Counter pgConcurrencyRejected;
    private final Counter pgCircuitRejected;
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
                             @Value("${payment.pg.merchant-concurrency-limit:0}") int merchantConcurrencyLimit,
                             @Value("${payment.pg.instances:1}") int instances,
                             @Value("${payment.pg.query-max-attempts:3}") int queryMaxAttempts,
                             @Value("${payment.pg.approve-resend-max-attempts:1}") int approveResendMaxAttempts,
                             ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this(delegate, perInstanceLimit(maxConcurrentCalls, merchantConcurrencyLimit, instances),
                meterRegistryProvider.getIfAvailable(io.micrometer.core.instrument.simple.SimpleMeterRegistry::new),
                queryMaxAttempts, approveResendMaxAttempts);
        log.info("PG 동시 호출 상한 {} (워커 보호 {}, PG 계약 한도 {}, 대수 {}), 승인 재전송 최대 {}회",
                perInstanceLimit(maxConcurrentCalls, merchantConcurrencyLimit, instances),
                maxConcurrentCalls, merchantConcurrencyLimit, instances, approveResendMaxAttempts);
    }

    /**
     * 인스턴스 하나의 PG 동시 호출 상한(#387). 워커 보호 상한(ADR-022, 40)은 인스턴스마다 맞지만 여러 대면 PG 가 받는 합이
     * 40 × 대수가 된다. PG 계약 한도를 알면 대수로 나눈 몫과 워커 보호 상한 중 작은 쪽을 쓴다.
     *
     * <p>Redis 전역 한도와 관측 지연 기반 적응형 한도도 쟀다. 전역 한도는 Redis 가 죽으면 결제를 전부 거절했고(닫힘)
     * 적응형은 PG 의 느림을 혼잡으로 읽어 성공이 절반이었다. 대수로 나누면 한 대가 빠졌을 때 합이 줄어 덜 받지만
     * 결과 모름은 만들지 않는다.
     *
     * @param workerCap     워커 보호 상한. 0 이하면 없음
     * @param merchantLimit PG 계약 동시 한도. 0 이하면 모름(나누지 않는다)
     * @param instances     API 인스턴스 수
     */
    static int perInstanceLimit(int workerCap, int merchantLimit, int instances) {
        if (merchantLimit <= 0) {
            return workerCap;
        }
        int share = Math.max(1, merchantLimit / Math.max(1, instances));
        return workerCap > 0 ? Math.min(workerCap, share) : share;
    }

    /** 공통 초기화 경로. 테스트가 운영과 동일한 계측 구성을 주입할 때 사용한다. 조회는 최대 3회, 승인 재전송은 최대 1회. */
    public ResilientPgClient(PgClient delegate, int maxConcurrentCalls, MeterRegistry meterRegistry) {
        this(delegate, maxConcurrentCalls, meterRegistry, 3, 1);
    }

    /**
     * 조회 시도 횟수까지 정한다(#334). 1 이면 재시도하지 않는다. 1 보다 작으면 기본값 3 으로 돈다.
     * 승인 재전송은 기본값 1회를 쓴다.
     */
    public ResilientPgClient(PgClient delegate, int maxConcurrentCalls, MeterRegistry meterRegistry, int queryMaxAttempts) {
        this(delegate, maxConcurrentCalls, meterRegistry, queryMaxAttempts, 1);
    }

    /**
     * 승인 재전송 횟수까지 정한다(#395). 0 이면 재전송하지 않는다(이전 동작). 음수는 0 으로 취급한다.
     */
    public ResilientPgClient(PgClient delegate, int maxConcurrentCalls, MeterRegistry meterRegistry,
                              int queryMaxAttempts, int approveResendMaxAttempts) {
        this.delegate = delegate;
        this.approveResendMaxAttempts = Math.max(0, approveResendMaxAttempts);
        // 0 이하면 상한을 걸지 않는다. 운영 기본값 40의 근거는 application.yml과 ADR-022에 있다.
        this.pgCallLimit = maxConcurrentCalls > 0 ? new Semaphore(maxConcurrentCalls) : null;
        // PG 로 실제로 나가 있는 승인 호출 수(#392). 상한이 없거나 가상 스레드로 워커 수가 사라지면 이 값이 곧 PG 가 받는 동시 호출이다
        Gauge.builder("payment.pg.approval.inflight", pgApprovalsInFlight, AtomicInteger::get)
                .description("PG 로 나가 응답을 기다리는 승인 호출 수")
                .register(meterRegistry);
        this.pgConcurrencyRejected = Counter.builder("payment.pg.approval.rejected")
                .tag("reason", "concurrency_limit")
                .description("PG 동시 호출 상한으로 승인 전에 거절된 요청 수")
                .register(meterRegistry);
        this.pgCircuitRejected = Counter.builder("payment.pg.approval.rejected")
                .tag("reason", "circuit_open")
                .description("승인 서킷이 열려 PG 에 보내지 않고 거절한 요청 수")
                .register(meterRegistry);
        this.pgUnknownFallback = Counter.builder("payment.pg.approval.unknown")
                .description("PG 승인 결과를 알 수 없어 UNKNOWN으로 보존한 요청 수")
                .register(meterRegistry);
        this.pgApprovalResent = Counter.builder("payment.pg.approval.resent")
                .description("승인 타임아웃 뒤 같은 멱등키로 재전송을 실제로 시도한 횟수(#395)")
                .register(meterRegistry);
        this.pgApprovalResendSucceeded = Counter.builder("payment.pg.approval.resent.succeeded")
                .description("재전송으로 UNKNOWN이 아닌 결과(SUCCESS/FAILED)를 받은 횟수 — 복구 배치를 기다리지 않고 그 자리에서 확정된 건수")
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

        // 승인 · 취소: 창 5 · 최소 3건. 쓰기 경로라 전면 장애를 빨리(3건) 알아채는 쪽을 둔다.
        CircuitBreakerConfig writeConfig = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(5)
                .minimumNumberOfCalls(3)
                .failureRateThreshold(50)                       // 절반 이상 실패하면 OPEN
                .waitDurationInOpenState(Duration.ofSeconds(5))
                .build();
        // 조회: 창 20 · 최소 10건. 창 5 는 독립 실패 10% 에서도 열려 복구 한 틱을 막았다(#334). 창 20 에서
        // 무작위 실패 10% 가 10건 이상 겹칠 확률은 약 7×10⁻⁶ 이다. 대가는 전면 장애에서 여는 데 10건이 든다.
        CircuitBreakerConfig queryConfig = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(20)
                .minimumNumberOfCalls(10)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(5))
                .build();
        this.approveCircuit = CircuitBreaker.of("pg-approve", writeConfig);
        this.cancelCircuit = CircuitBreaker.of("pg-cancel", writeConfig);
        this.queryCircuit = CircuitBreaker.of("pg-query", queryConfig);

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
        pgApprovalsInFlight.incrementAndGet();
        PgApproveResult result;
        try {
            result = approveCircuit.executeSupplier(() -> delegate.approve(command));
        } catch (CallNotPermittedException open) {
            // 서킷이 열려 호출이 나가지 않았다 → PG 에 닿지 않은 것이 보장된다 → 확정 실패(상한과 같은 규칙, #372).
            // 예전에는 미확정으로 적어 조회할 대상이 없는 유령 미확정을 만들었다.
            pgCircuitRejected.increment();
            log.warn("PG 서킷 오픈 — 승인을 보내지 않고 확정 실패: {}", command.orderNo());
            result = PgApproveResult.failed("PG 승인 서킷 오픈: 요청을 보내지 않았다");
        } catch (RuntimeException ex) {
            // 예외를 실패로 단정하지 않는다 — PG에서 처리됐을 수도 있다 → UNKNOWN
            log.warn("PG 승인 호출 예외 — 미확정 처리: {}", ex.getMessage());
            result = PgApproveResult.timeout("PG 오류로 승인 미확정: " + ex.getMessage());
        } finally {
            pgApprovalsInFlight.decrementAndGet();
            if (pgCallLimit != null) {
                pgCallLimit.release();
            }
        }
        // 최초 시도가 타임아웃(UNKNOWN)이면 같은 멱등키로 재전송을 시도한다(#395). permit 을 반납한
        // 뒤에 독립적으로 다시 거치므로, 재전송도 상한·서킷 규칙을 최초 시도와 똑같이 받는다.
        if (result.outcome() == PgOutcome.TIMEOUT) {
            result = resendIfPossible(command, result);
        }
        if (result.outcome() == PgOutcome.TIMEOUT) {
            pgUnknownFallback.increment();
        }
        timer.stop(pgApprovalLatency);
        return result;
    }

    /**
     * 최초 시도가 타임아웃이면 같은 커맨드(=같은 멱등키)로 최대 {@link #approveResendMaxAttempts}번
     * 다시 보낸다(#395, 이슈 참고). 재전송도 동시 호출 상한과 승인 서킷을 그대로 거친다 — 상한을 못
     * 채우거나 서킷이 열려 있으면 재전송하지 않고 원래 UNKNOWN을 그대로 돌려준다. 재전송이 PG에
     * 닿아 SUCCESS/FAILED로 확정되면 그 결과를 쓰고, 재전송도 타임아웃이거나 PG가 아직 처리 중이라고
     * 답하면(토스 409 {@code IDEMPOTENT_REQUEST_PROCESSING}은 {@link TossErrorCodes}가 RETRYABLE로
     * 분류해 예외로 던지므로 여기 {@code catch (RuntimeException)}로 들어온다) UNKNOWN을 유지한다.
     */
    private PgApproveResult resendIfPossible(PgApproveCommand command, PgApproveResult original) {
        PgApproveResult current = original;
        for (int attempt = 1;
             attempt <= approveResendMaxAttempts && current.outcome() == PgOutcome.TIMEOUT;
             attempt++) {
            // 지터는 permit 을 잡기 전에 쉰다 — 쉬는 동안 자리를 묵혀 두지 않는다. 그 사이 다른 요청이
            // 상한을 채우면 아래 tryAcquire 가 그걸 그대로 반영한다.
            sleepBeforeResend();
            if (pgCallLimit != null && !pgCallLimit.tryAcquire()) {
                log.info("PG 재전송 보류 — 동시 호출 상한, 기존 UNKNOWN 유지: {}", command.orderNo());
                break;
            }
            pgApprovalsInFlight.incrementAndGet();
            try {
                pgApprovalResent.increment();
                current = approveCircuit.executeSupplier(() -> delegate.approve(command));
                if (current.outcome() != PgOutcome.TIMEOUT) {
                    pgApprovalResendSucceeded.increment();
                }
            } catch (CallNotPermittedException open) {
                // 재전송 시점에 서킷이 열려 있다 → 재전송 없이 기존 UNKNOWN 유지(원래 시도는 이미 PG 에 닿았을 수 있다).
                log.info("PG 재전송 보류 — 서킷 오픈, 기존 UNKNOWN 유지: {}", command.orderNo());
                break;
            } catch (RuntimeException ex) {
                log.warn("PG 재전송도 실패 — 미확정 유지: {}", ex.getMessage());
                current = PgApproveResult.timeout("재전송 후에도 미확정: " + ex.getMessage());
            } finally {
                pgApprovalsInFlight.decrementAndGet();
                if (pgCallLimit != null) {
                    pgCallLimit.release();
                }
            }
        }
        return current;
    }

    /** 재전송 전 짧은 지터. 같은 순간 몰린 타임아웃들이 한꺼번에 PG를 다시 두드리는 것을 피한다. */
    private void sleepBeforeResend() {
        try {
            Thread.sleep(java.util.concurrent.ThreadLocalRandom.current().nextInt(20, 60));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public PgCancelResult cancel(PgCancelCommand command) {
        // 취소도 서킷으로 보호하되 재시도는 하지 않는다(호출부가 실패를 처리).
        return cancelCircuit.executeSupplier(() -> delegate.cancel(command));
    }

    @Override
    public PgQueryResult query(String paymentKey) {
        Supplier<PgQueryResult> guarded = Retry.decorateSupplier(queryRetry,
                () -> queryCircuit.executeSupplier(() -> delegate.query(paymentKey)));
        // 조회 실패는 예외로 전파 — 복구 배치가 건별로 잡아 다음 주기에 다시 시도한다.
        return guarded.get();
    }

    CircuitBreaker approveCircuit() {
        return approveCircuit;
    }

    CircuitBreaker queryCircuit() {
        return queryCircuit;
    }

    /** 테스트 전용. 재전송이 동시 호출 상한을 실제로 거치는지 확인할 때 직접 점유해 본다. */
    Semaphore concurrencyLimit() {
        return pgCallLimit;
    }
}
