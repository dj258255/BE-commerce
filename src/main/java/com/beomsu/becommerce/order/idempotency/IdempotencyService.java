package com.beomsu.becommerce.order.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
 */
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private static final int DEADLOCK_MAX_ATTEMPTS = 3;

    private final IdempotencyRepository repository;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

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
            return handleExisting(existing.get(), requestHash, responseType);
        }

        // 2. 신규 — PROCESSING 삽입. 유니크 제약이 동시 요청 중 한 건만 통과시킨다.
        IdempotencyRecord record;
        try {
            record = repository.saveAndFlush(
                    IdempotencyRecord.start(key, apiPath, httpMethod, requestHash));
        } catch (DataIntegrityViolationException race) {
            // 다른 요청이 같은 순간 먼저 삽입함 → 그 레코드로 판정
            IdempotencyRecord other = repository
                    .findByIdempotencyKeyAndApiPathAndHttpMethod(key, apiPath, httpMethod)
                    .orElseThrow(() -> IdempotencyException.processing(key));
            meterRegistry.counter("idempotency.concurrent.race").increment();
            return handleExisting(other, requestHash, responseType);
        }

        // 3. 실제 작업 실행 후 응답 저장 — 데드락(transient)은 짧게 재시도한다
        try {
            T result = executeWithDeadlockRetry(action);
            record.complete(serialize(result));
            repository.save(record);
            return result;
        } catch (RuntimeException e) {
            // 재시도 소진/일반 실패 시 레코드를 제거해 클라이언트가 다시 시도할 수 있게 한다.
            //
            // 주의: action이 하나의 트랜잭션이라고 가정하면 안 된다. 체크아웃은 예약(tx) → PG 승인
            // (tx 밖) → 확정(tx) 3단계 사가라, 예약이 커밋된 뒤 뒤 단계에서 예외가 나면 롤백되는 것은
            // 마지막 트랜잭션뿐이다. 주문은 PAYMENT_IN_PROGRESS로, 포인트·월렛은 선점된 채 남는다.
            // 그래도 안전한 이유는 두 겹이다. 재시도가 들어와도 order.startPayment()의 조건부 전이가
            // 이미 진행 중인 주문을 막고, 멈춘 주문은 CheckoutRecoveryService가 PG 조회로 완결하거나
            // 되돌린다. 이 레코드 삭제는 "재시도 허용"이지 "이전 시도 무효화"가 아니다.
            repository.delete(record);
            throw e;
        }
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
