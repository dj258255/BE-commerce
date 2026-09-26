package com.beomsu.becommerce.order.idempotency;

import com.beomsu.becommerce.order.internal.CheckoutResult;
import com.beomsu.becommerce.order.internal.OrderStatus;
import com.beomsu.becommerce.payment.PaymentStatus;
import com.beomsu.becommerce.testsupport.SharedContainers;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 멱등 처리권 만료를 실 MySQL 로 확인한다(#369). 판정 기준 1~4 를 그대로 옮겼다.
 *
 * <p>크래시는 "PROCESSING 행이 남고 처리권 만료 시각이 지난 상태"로 흉내 낸다. 프로세스를 실제로 죽이지
 * 않아도 남는 상태가 같다. 만료는 {@code lease_until} 을 과거로 당겨 만든다(3분을 기다리지 않는다).
 * 시각은 DB 의 {@code NOW(6)} 을 쓴다. 연결({@code serverTimezone=UTC})과 Hibernate({@code time_zone: UTC})가
 * 모두 UTC 라 앱이 쓰는 {@code Instant} 와 같은 기준이다.
 */
@Tag("integration")
@SpringBootTest
@DisplayName("멱등 처리권 만료 — 요청 도중 죽은 처리권을 한 요청만 넘겨받는가")
class IdempotencyLeaseIntegrationTest {

    private static final String PATH = "/api/v1/payments/confirm";
    private static final Map<String, Object> REQUEST = Map.of("orderNo", "o-lease", "amount", 20_000);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry props) {
        SharedContainers.register(props, "IdempotencyLease");
    }

    @Autowired
    IdempotencyService service;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MeterRegistry meterRegistry;

    private static CheckoutResult result(String message) {
        return new CheckoutResult("o-lease", OrderStatus.PAID, PaymentStatus.DONE, message);
    }

    private void expireLease(String key) {
        int updated = jdbc.update("""
                UPDATE idempotency_keys SET lease_until = NOW(6) - INTERVAL 1 SECOND
                 WHERE idempotency_key = ? AND status = 'PROCESSING'
                """, key);
        assertThat(updated).as("PROCESSING 행이 남아 있어야 크래시를 흉내 낼 수 있다").isEqualTo(1);
    }

    private void insertProcessing(String key, String leaseUntilExpr) {
        jdbc.update("""
                INSERT INTO idempotency_keys (idempotency_key, api_path, http_method, request_hash, status,
                                              response_body, created_at, expires_at, lease_until, version)
                VALUES (?, ?, 'POST', ?, 'PROCESSING', NULL, NOW(6), NOW(6) + INTERVAL 15 DAY,
                """ + leaseUntilExpr + ", 0)", key, PATH, requestHash());
    }

    private String requestHash() {
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256").digest(
                    new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()
                            .writeValueAsString(REQUEST).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(h);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, Object> row(String key) {
        return jdbc.queryForMap("SELECT status, response_body, version FROM idempotency_keys WHERE idempotency_key = ?",
                key);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("기준 1: 만료된 PROCESSING 은 같은 키 재요청이 정확히 한 번 다시 실행하고 DONE 을 저장한다")
    void crashedRequestIsTakenOverOnce() {
        String key = "lease-" + UUID.randomUUID();
        insertProcessing(key, "NOW(6) - INTERVAL 1 SECOND");
        AtomicInteger calls = new AtomicInteger();

        CheckoutResult got = service.execute(key, PATH, "POST", REQUEST, CheckoutResult.class,
                () -> { calls.incrementAndGet(); return result("넘겨받아 실행"); });

        assertThat(calls.get()).isEqualTo(1);
        assertThat(got.message()).isEqualTo("넘겨받아 실행");
        Map<String, Object> row = row(key);
        assertThat(row.get("status")).isEqualTo("DONE");
        assertThat((String) row.get("response_body")).contains("넘겨받아 실행");

        // 넘겨받은 뒤의 같은 키는 저장된 응답을 재반환한다(다시 실행하지 않는다)
        CheckoutResult replay = service.execute(key, PATH, "POST", REQUEST, CheckoutResult.class,
                () -> { calls.incrementAndGet(); return result("실행되면 안 됨"); });
        assertThat(replay.message()).isEqualTo("넘겨받아 실행");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("기준 2: 처리권이 살아 있으면 지금처럼 409, 작업 실행 0번")
    void liveLeaseStillRejects() {
        String key = "lease-" + UUID.randomUUID();
        insertProcessing(key, "NOW(6) + INTERVAL 3 MINUTE");
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> service.execute(key, PATH, "POST", REQUEST, CheckoutResult.class,
                () -> { calls.incrementAndGet(); return result("실행되면 안 됨"); }))
                .isInstanceOf(IdempotencyException.class)
                .satisfies(e -> assertThat(((IdempotencyException) e).code())
                        .isEqualTo("IDEMPOTENT_REQUEST_PROCESSING"));
        assertThat(calls.get()).isZero();
        assertThat(row(key).get("status")).isEqualTo("PROCESSING");
    }

    @Test
    @DisplayName("기준 3: 만료 뒤 같은 키 20개 동시 재요청 — 작업 실행 정확히 1번, 나머지 19개는 409")
    void concurrentRetriesTakeOverExactlyOnce() throws Exception {
        String key = "lease-" + UUID.randomUUID();
        insertProcessing(key, "NOW(6) - INTERVAL 1 SECOND");
        int clients = 20;
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch allRejectedOrRunning = new CountDownLatch(clients - 1);
        AtomicInteger rejected = new AtomicInteger();

        Supplier<CheckoutResult> slowAction = () -> {
            calls.incrementAndGet();
            await(allRejectedOrRunning);   // 나머지가 모두 판정을 받을 때까지 끝나지 않는다
            return result("한 번만");
        };

        ExecutorService pool = Executors.newFixedThreadPool(clients);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < clients; i++) {
            futures.add(pool.submit(() -> {
                await(start);
                try {
                    service.execute(key, PATH, "POST", REQUEST, CheckoutResult.class, slowAction);
                } catch (IdempotencyException e) {
                    if ("IDEMPOTENT_REQUEST_PROCESSING".equals(e.code())) {
                        rejected.incrementAndGet();
                        allRejectedOrRunning.countDown();
                    } else {
                        throw e;
                    }
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        System.out.printf("%n  만료 뒤 동시 재요청 %d개 → 작업 실행 %d번, 409 %d개%n", clients, calls.get(), rejected.get());
        assertThat(calls.get()).isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(clients - 1);
        assertThat(row(key).get("status")).isEqualTo("DONE");
    }

    @Test
    @DisplayName("기준 4: 넘겨받은 뒤 원래 요청이 늦게 끝나도 새 주인의 응답을 덮어쓰지 못한다")
    void lateOriginalCannotOverwrite() throws Exception {
        String key = "lease-" + UUID.randomUUID();
        double lostBefore = meterRegistry.counter("idempotency.lease.lost").count();
        CountDownLatch originalInside = new CountDownLatch(1);
        CountDownLatch releaseOriginal = new CountDownLatch(1);

        ExecutorService original = Executors.newSingleThreadExecutor();
        Future<CheckoutResult> late = original.submit(() -> service.execute(key, PATH, "POST", REQUEST,
                CheckoutResult.class, () -> {
                    originalInside.countDown();
                    await(releaseOriginal);   // 처리권 만료보다 오래 걸린 요청
                    return result("늦은 원래 요청");
                }));
        await(originalInside);
        expireLease(key);

        CheckoutResult winner = service.execute(key, PATH, "POST", REQUEST, CheckoutResult.class,
                () -> result("새 주인"));
        releaseOriginal.countDown();
        CheckoutResult lateResult = late.get(10, TimeUnit.SECONDS);
        original.shutdown();

        assertThat(winner.message()).isEqualTo("새 주인");
        assertThat(lateResult.message()).isEqualTo("늦은 원래 요청");   // 자기 결과는 돌려받지만
        Map<String, Object> row = row(key);
        assertThat(row.get("status")).isEqualTo("DONE");
        assertThat((String) row.get("response_body")).contains("새 주인");   // 저장된 응답은 새 주인의 것
        assertThat(meterRegistry.counter("idempotency.lease.lost").count()).isEqualTo(lostBefore + 1);
    }
}
