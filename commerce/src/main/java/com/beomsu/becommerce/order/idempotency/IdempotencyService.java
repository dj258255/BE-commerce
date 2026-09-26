package com.beomsu.becommerce.order.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * 멱등 실행기 — "따닥" 중복결제 방지 + 타임아웃 후 안전 재시도.
 *
 * <p>같은 멱등키의 요청은 딱 한 번만 실제 실행되고, 이후 재요청에는 <b>첫 응답을 그대로 재반환</b>한다.
 * 동시 요청은 {@code idempotency_keys}의 유니크 제약이 한 건만 통과시킨다 — INSERT 성공이 곧 처리권
 * 획득이라, 별도 분산락이 필요 없다.
 *
 * <p>이 메서드는 트랜잭션으로 감싸지 않는다. PROCESSING 레코드 삽입이 즉시 커밋돼야 동시 요청이
 * 그것을 보고 409로 막히기 때문이다. 실제 작업({@code action})은 자신의 트랜잭션을 갖는다.
 *
 * <p><b>데드락 재시도 정책</b> — k6 스파이크(150VU) 실측에서 rate limiter가 윈도우 경계마다 통과
 * 요청을 버스트로 내보내자, 동시 커밋들이 {@code event_publication}(Modulith outbox) INSERT에서
 * MySQL 데드락(SQLSTATE 40001)으로 충돌했다(5xx 0.55%). MySQL이 "try restarting transaction"이라
 * 말하는 <b>일시적(transient) 실패</b>라, 트랜잭션 경계인 여기서 짧은 지터 백오프 후 최대
 * {@value #DEADLOCK_MAX_ATTEMPTS}회 재실행한다. 이것이 안전한 이유:
 * <ol>
 *   <li>데드락은 타임아웃과 달리 <b>결과가 확정된 실패</b>다 — 트랜잭션 전체가 롤백됐음을 DB가
 *       보장한다. "승인은 재시도 금지(UNKNOWN)" 규칙은 <i>결과를 모르는</i> 타임아웃용이라 충돌하지 않는다.</li>
 *   <li>action 재실행 시 유일한 외부 부수효과는 PG approve 재호출인데, 그 호출은
 *       <b>{@code Idempotency-Key} 헤더(=orderNo)를 실어 보낸다</b>. 토스페이먼츠가 문서로
 *       보장하는 건 "같은 파라미터"가 아니라 <b>같은 멱등키면 같은 응답</b>이라는 계약이다.
 *       그래서 안전한 근거는 파라미터가 아니라 그 헤더다. (FakePg는 결정적이라 재실행이 같다.)</li>
 * </ol>
 *
 * <p><b>다만 DB 롤백이 PG 호출을 되돌리지는 않는다.</b> 데드락으로 트랜잭션이 롤백돼도
 * 이미 나간 승인은 PG에 남아 있다. 재실행이 안전한 것은 그 승인을 <b>되돌려서</b>가 아니라
 * 멱등키 덕에 <b>같은 승인을 다시 받기</b> 때문이다. 이 구분이 흐려지면 "롤백됐으니 깨끗하다"는
 * 잘못된 확신으로 이어진다.
 * <ol style="display:none">
 * </ol>
 * 재시도 동안 PROCESSING 레코드는 유지되므로 동시 중복 요청은 계속 409로 막힌다(의도).
 *
 * <p><b>처리권 만료(#369)</b> — PROCESSING 레코드는 작업이 예외로 끝날 때만 지운다. 요청 도중 프로세스가
 * 죽으면 레코드가 남아 같은 키가 {@code expiresAt}(15일)까지 409 를 받았다. 그래서 처리권에 만료
 * ({@code leaseUntil})를 두고, 만료된 PROCESSING 레코드는 다음 같은 키 요청 <b>하나</b>가 조건부 UPDATE 로
 * 넘겨받아 작업을 다시 실행한다(Airbnb Orpheus 의 리스와 같은 자리). 다시 실행해도 이중 결제로 가지 않는
 * 근거는 작업 쪽에 있다. 결제 확정은 실행하자마자 앞 시도를 PG 조회로 해소하고
 * {@code ORDER_ALREADY_PAID} · {@code PAYMENT_RESULT_PENDING} 을 돌려준다. 새 승인은 나가지 않는다.
 *
 * <p>만료 기본값 {@link #DEFAULT_LEASE} 는 요청 하나의 최악 시간보다 길다. 데드락 재시도 3회 ×
 * (PG 조회 3회 × 7초(연결 2 + 읽기 5) + 승인 7초) ≈ 84초의 두 배 이상이다. 이보다 짧으면 살아 있는
 * 느린 요청을 가로챈다. 넘겨받은 뒤 원래 요청이 늦게 끝나면 버전 충돌로 저장이 막히고 새 주인의 응답이 남는다.
 */
@Slf4j
@Service
public class IdempotencyService {

    private static final int DEADLOCK_MAX_ATTEMPTS = 3;

    /** 처리권 만료 기본값. 근거는 클래스 주석. */
    static final Duration DEFAULT_LEASE = Duration.ofMinutes(3);

    private final IdempotencyRepository repository;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final Duration lease;
    private final Clock clock;

    public IdempotencyService(IdempotencyRepository repository, ObjectMapper objectMapper,
                              MeterRegistry meterRegistry) {
        this(repository, objectMapper, meterRegistry, DEFAULT_LEASE, Clock.systemUTC());
    }

    @Autowired
    public IdempotencyService(IdempotencyRepository repository, ObjectMapper objectMapper,
                              MeterRegistry meterRegistry,
                              @Value("${app.idempotency.processing-lease:3m}") Duration lease) {
        this(repository, objectMapper, meterRegistry, lease, Clock.systemUTC());
    }

    IdempotencyService(IdempotencyRepository repository, ObjectMapper objectMapper,
                       MeterRegistry meterRegistry, Duration lease, Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.lease = lease;
        this.clock = clock;
    }

    public <T> T execute(String key, String apiPath, String httpMethod,
                         Object requestBody, Class<T> responseType, Supplier<T> action) {
        if (key == null || key.isBlank()) {
            throw IdempotencyException.invalidKey("멱등키(Idempotency-Key)가 필요합니다.");
        }
        if (key.length() > 300) {
            throw IdempotencyException.invalidKey("멱등키는 300자를 넘을 수 없습니다.");
        }

        String requestHash = sha256(serialize(requestBody));

        // 1. 기존 레코드가 있으면 재반환/거절 판정
        Optional<IdempotencyRecord> existing =
                repository.findByIdempotencyKeyAndApiPathAndHttpMethod(key, apiPath, httpMethod);
        if (existing.isPresent()) {
            meterRegistry.counter("idempotency.existing.request").increment();
            IdempotencyRecord current = existing.get();
            // 처리하던 요청이 죽어 처리권이 만료됐으면 넘겨받아 다시 실행한다(#369). 다른 본문이면 넘겨받지 않고 422.
            if (current.matches(requestHash)) {
                Optional<IdempotencyRecord> taken = takeOverIfExpired(current);
                if (taken.isPresent()) {
                    return executeAndStore(taken.get(), action);
                }
            }
            return handleExisting(current, requestHash, responseType);
        }

        // 2. 신규 — PROCESSING 삽입. 유니크 제약이 동시 요청 중 한 건만 통과시킨다.
        IdempotencyRecord record;
        try {
            record = repository.saveAndFlush(
                    IdempotencyRecord.start(key, apiPath, httpMethod, requestHash, clock.instant(), lease));
        } catch (DataIntegrityViolationException race) {
            // 다른 요청이 같은 순간 먼저 삽입함 → 그 레코드로 판정
            IdempotencyRecord other = repository
                    .findByIdempotencyKeyAndApiPathAndHttpMethod(key, apiPath, httpMethod)
                    .orElseThrow(() -> IdempotencyException.processing(key));
            meterRegistry.counter("idempotency.concurrent.race").increment();
            return handleExisting(other, requestHash, responseType);
        }

        // 3. 실제 작업 실행 후 응답 저장
        return executeAndStore(record, action);
    }

    /**
     * 작업을 실행하고 응답을 저장한다. 데드락(transient)은 짧게 재시도한다.
     *
     * <p>작업이 실패하면 레코드를 지워 클라이언트가 다시 시도할 수 있게 한다.
     * 주의: action이 하나의 트랜잭션이라고 가정하면 안 된다. 체크아웃은 예약(tx) → PG 승인
     * (tx 밖) → 확정(tx) 3단계 사가라, 예약이 커밋된 뒤 뒤 단계에서 예외가 나면 롤백되는 것은
     * 마지막 트랜잭션뿐이다. 주문은 PAYMENT_IN_PROGRESS로, 포인트·월렛은 선점된 채 남는다.
     * 그래도 안전한 이유는 두 겹이다. 재시도가 들어와도 order.startPayment()의 조건부 전이가
     * 이미 진행 중인 주문을 막고, 멈춘 주문은 CheckoutRecoveryService가 PG 조회로 완결하거나
     * 되돌린다. 이 레코드 삭제는 "재시도 허용"이지 "이전 시도 무효화"가 아니다.
     *
     * <p>처리권을 이미 넘겨준 뒤라면(버전 충돌) 저장도 삭제도 하지 않는다. 레코드는 새 주인의 것이다.
     */
    private <T> T executeAndStore(IdempotencyRecord record, Supplier<T> action) {
        T result;
        String body;
        try {
            result = executeWithDeadlockRetry(action);
            body = serialize(result);
        } catch (RuntimeException e) {
            releaseIfStillOwned(record);
            throw e;
        }
        record.complete(body);
        try {
            repository.save(record);
        } catch (ObjectOptimisticLockingFailureException lost) {
            meterRegistry.counter("idempotency.lease.lost").increment();
            log.warn("멱등 처리권을 잃은 뒤 작업이 끝나 응답을 저장하지 않음 key={}", record.getIdempotencyKey());
        }
        return result;
    }

    private void releaseIfStillOwned(IdempotencyRecord record) {
        try {
            repository.delete(record);
        } catch (ObjectOptimisticLockingFailureException lost) {
            meterRegistry.counter("idempotency.lease.lost").increment();
        }
    }

    /** 처리권이 만료된 PROCESSING 레코드를 넘겨받는다. 같은 순간 여럿이 시도하면 한 요청만 얻는다. */
    private Optional<IdempotencyRecord> takeOverIfExpired(IdempotencyRecord record) {
        Instant now = clock.instant();
        if (!record.leaseExpired(now)) {
            return Optional.empty();
        }
        int updated = repository.takeOverExpiredLease(record.getId(), record.getVersion(),
                IdempotencyRecord.Status.PROCESSING, now, now.plus(lease));
        if (updated == 0) {
            return Optional.empty();   // 다른 요청이 먼저 넘겨받았다 → 처리 중(409)
        }
        meterRegistry.counter("idempotency.lease.takeover").increment();
        log.warn("만료된 멱등 처리권을 넘겨받아 다시 실행 key={}", record.getIdempotencyKey());
        return repository.findById(record.getId());
    }

    /**
     * action을 실행하되, MySQL 데드락(1213)으로 롤백되면 지터 백오프 후 재실행한다.
     * DeadlockLoserDataAccessException은 CannotAcquireLockException의 하위라 함께 잡힌다.
     */
    private <T> T executeWithDeadlockRetry(Supplier<T> action) {
        for (int attempt = 1; ; attempt++) {
            try {
                return action.get();
            } catch (CannotAcquireLockException e) {
                // 데드락은 전체 롤백된 '확정된 실패'라 재실행이 안전하다(결과 미상의 타임아웃과 다름).
                if (attempt >= DEADLOCK_MAX_ATTEMPTS) {
                    throw e;
                }
                meterRegistry.counter("idempotency.deadlock.retry").increment();
                sleepBriefly(attempt, e);
            }
        }
    }

    /** (20~50ms) × 시도횟수 지터 백오프. 인터럽트되면 무한 대기하지 않고 인터럽트 복원 후 원 예외를 던진다. */
    private void sleepBriefly(int attempt, RuntimeException cause) {
        long millis = (long) ThreadLocalRandom.current().nextInt(20, 51) * attempt;
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw cause;
        }
    }

    private <T> T handleExisting(IdempotencyRecord record, String requestHash, Class<T> responseType) {
        if (!record.matches(requestHash)) {
            meterRegistry.counter("idempotency.conflict").increment();
            throw IdempotencyException.reused(record.getIdempotencyKey());   // 같은 키 + 다른 본문 → 422
        }
        if (!record.isDone()) {
            meterRegistry.counter("idempotency.processing").increment();
            throw IdempotencyException.processing(record.getIdempotencyKey()); // 처리 중 → 409
        }
        meterRegistry.counter("idempotency.replay").increment();
        return deserialize(record.getResponseBody(), responseType);            // 첫 응답 재반환
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("멱등 처리 직렬화 실패", e);
        }
    }

    private <T> T deserialize(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("멱등 응답 역직렬화 실패", e);
        }
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
