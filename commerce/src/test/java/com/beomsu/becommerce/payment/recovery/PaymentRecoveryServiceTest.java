package com.beomsu.becommerce.payment.recovery;

import org.springframework.data.domain.Pageable;

import com.beomsu.becommerce.payment.PaymentStatus;
import com.beomsu.becommerce.payment.internal.PaymentRepository;
import com.beomsu.becommerce.payment.PaymentConfirmedEvent;
import com.beomsu.becommerce.payment.internal.Payment;
import com.beomsu.becommerce.payment.pg.PgClient;
import com.beomsu.becommerce.payment.pg.PgPaymentStatus;
import com.beomsu.becommerce.payment.pg.PgQueryResult;
import com.beomsu.becommerce.shared.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PaymentRecoveryServiceTest {

    private PaymentRepository repository;
    private PgClient pg;
    private ApplicationEventPublisher events;
    private PaymentRecoveryService service;

    @BeforeEach
    void setUp() {
        repository = mock(PaymentRepository.class);
        pg = mock(PgClient.class);
        events = mock(ApplicationEventPublisher.class);
        service = new PaymentRecoveryService(repository, pg, events);
    }

    /** UNKNOWN 상태로 방치된 결제 하나를 만들어 리포지토리가 돌려주게 한다. */
    private Payment unknownPayment(String paymentKey) {
        Payment p = Payment.initiate("order-1", Money.krw(10_000));
        p.startApproval(paymentKey);
        p.markUnknown("PG 응답 타임아웃");
        when(repository.findRecoverableUnknown(any(Instant.class), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(p));                        // 기본 정책 backoff 의 쿼리(#248)
        when(repository.findByStatusAndRequestedAtBefore(eq(PaymentStatus.UNKNOWN), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(p));                        // unordered 로 바꾼 테스트용
        return p;
    }

    @Test
    @DisplayName("PG에 승인돼 있으면 전진 복구(DONE) + 완료 이벤트 발행")
    void recoverForwardWhenPgApproved() {
        Payment p = unknownPayment("pk-1");
        when(pg.query("pk-1", "TOSS_PAYMENTS")).thenReturn(new PgQueryResult(PgPaymentStatus.APPROVED, "CARD"));

        int recovered = service.recoverUnknownPayments();

        assertThat(recovered).isEqualTo(1);
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.DONE);
        assertThat(p.getMethod()).isEqualTo("CARD");
        // 복구 상태 전이가 명시 saveAndFlush로 영속된다(OSIV off에서 dirty-checking 자동 flush에 의존하지 않음).
        verify(repository).saveAndFlush(p);
        verify(events).publishEvent(any(PaymentConfirmedEvent.class));
    }

    @Test
    @DisplayName("PG에 결제가 없으면 ABORTED (승인이 실제로 안 됨), 이벤트 미발행")
    void abortWhenPgNotFound() {
        Payment p = unknownPayment("pk-2");
        when(pg.query("pk-2", "TOSS_PAYMENTS")).thenReturn(new PgQueryResult(PgPaymentStatus.NOT_FOUND, null));

        service.recoverUnknownPayments();

        assertThat(p.getStatus()).isEqualTo(PaymentStatus.ABORTED);
        verify(repository).saveAndFlush(p); // 복구 상태 전이 명시 영속
        verify(events, never()).publishEvent(any());
    }

    @Test
    @DisplayName("PG에서 이미 취소됐으면 CANCELED(망취소 반영)")
    void networkCancelWhenPgCanceled() {
        Payment p = unknownPayment("pk-3");
        when(pg.query("pk-3", "TOSS_PAYMENTS")).thenReturn(new PgQueryResult(PgPaymentStatus.CANCELED, null));

        service.recoverUnknownPayments();

        assertThat(p.getStatus()).isEqualTo(PaymentStatus.CANCELED);
        verify(repository).saveAndFlush(p); // 복구 상태 전이 명시 영속
    }

    // --- 확정 못 한 건과 읽는 순서(#248) ---

    @Test
    @DisplayName("PG 가 진행 중이면 확정하지 않고, 복구 건수에 세지 않으며, 시도 횟수만 남긴다")
    void inProgressIsDeferredNotCounted() {
        ReflectionTestUtils.setField(service, "policy", "unordered");
        Payment p = unknownPayment("pk-9");
        when(pg.query("pk-9", "TOSS_PAYMENTS")).thenReturn(new PgQueryResult(PgPaymentStatus.IN_PROGRESS, null));

        int recovered = service.recoverUnknownPayments();

        assertThat(recovered).isZero();
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(p.getRecoveryAttempts()).isEqualTo(1);
        assertThat(p.getRecoveryNextAt()).isNull();          // unordered 는 미루지 않는다
        verify(repository).saveAndFlush(p);
    }

    @Test
    @DisplayName("backoff: 확정 못 한 건은 1·2·4·8분 뒤로 밀고 10분에서 멈춘다. 조회 실패도 같다")
    void backoffPushesNextAttempt() {
        ReflectionTestUtils.setField(service, "policy", "backoff");
        Payment p = Payment.initiate("order-1", Money.krw(10_000));
        p.startApproval("pk-10");
        p.markUnknown("PG 응답 타임아웃");
        when(repository.findRecoverableUnknown(any(Instant.class), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(p));
        when(pg.query("pk-10", "TOSS_PAYMENTS")).thenReturn(new PgQueryResult(PgPaymentStatus.IN_PROGRESS, null));

        long[] expectedMinutes = {1, 2, 4, 8, 10, 10};
        for (long minutes : expectedMinutes) {
            Instant before = Instant.now();
            service.recoverUnknownPayments();
            assertThat(p.getRecoveryNextAt()).isBetween(before.plusSeconds(minutes * 60 - 1),
                    Instant.now().plusSeconds(minutes * 60 + 1));
        }
        assertThat(p.getRecoveryAttempts()).isEqualTo(expectedMinutes.length);

        when(pg.query("pk-10", "TOSS_PAYMENTS")).thenThrow(new IllegalStateException("PG 조회 타임아웃"));
        service.recoverUnknownPayments();
        assertThat(p.getRecoveryAttempts()).isEqualTo(expectedMinutes.length + 1);
        verify(repository, never()).findByStatusAndRequestedAtBefore(any(), any(), any());
    }

    @Test
    @DisplayName("backoff 간격은 설정으로 바꿀 수 있고 0 이나 음수면 기본값(1분·상한 10분)으로 돈다(#330)")
    void backoffIntervalsAreConfigurable() {
        ReflectionTestUtils.setField(service, "backoffBase", Duration.ofSeconds(30));
        ReflectionTestUtils.setField(service, "backoffCap", Duration.ofMinutes(2));
        assertThat(List.of(0, 1, 2, 3, 30).stream().map(service::backoffDelay).toList()).containsExactly(
                Duration.ofSeconds(30), Duration.ofMinutes(1), Duration.ofMinutes(2), Duration.ofMinutes(2), Duration.ofMinutes(2));

        ReflectionTestUtils.setField(service, "backoffBase", Duration.ZERO);
        ReflectionTestUtils.setField(service, "backoffCap", Duration.ofMinutes(-1));
        assertThat(service.backoffDelay(0)).isEqualTo(Duration.ofMinutes(1));
        assertThat(service.backoffDelay(10)).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    @DisplayName("backoff-jitter 가 0(기본값)이면 같은 attempts 는 언제나 같은 간격이다(#399)")
    void backoffJitterZeroIsDeterministic() {
        ReflectionTestUtils.setField(service, "backoffBase", Duration.ofMinutes(1));
        ReflectionTestUtils.setField(service, "backoffCap", Duration.ofMinutes(10));
        Duration first = service.backoffDelay(2);
        for (int i = 0; i < 20; i++) {
            assertThat(service.backoffDelay(2)).isEqualTo(first);
        }
        assertThat(first).isEqualTo(Duration.ofMinutes(4));
    }

    @Test
    @DisplayName("backoff-jitter 를 켜면 같은 attempts 도 간격이 [간격×(1−jitter), 간격×(1+jitter)] 안에서 흩어진다(#399)")
    void backoffJitterSpreadsWithinBounds() {
        ReflectionTestUtils.setField(service, "backoffBase", Duration.ofMinutes(1));
        ReflectionTestUtils.setField(service, "backoffCap", Duration.ofMinutes(10));
        ReflectionTestUtils.setField(service, "backoffJitter", 0.5);

        Duration nominal = Duration.ofMinutes(4); // attempts=2: 1분 × 2^2
        Duration lowerBound = nominal.dividedBy(2);      // × (1 − 0.5)
        Duration upperBound = nominal.plus(lowerBound);  // × (1 + 0.5)

        java.util.Set<Duration> seen = new java.util.HashSet<>();
        for (int i = 0; i < 200; i++) {
            Duration delay = service.backoffDelay(2);
            assertThat(delay).isBetween(lowerBound, upperBound);
            seen.add(delay);
        }
        assertThat(seen.size()).isGreaterThan(50); // 결정적이면 1이다. 흩어짐을 본다
    }

    @Test
    @DisplayName("backoff-jitter 가 상한을 다시 넘기지 않는다(#399)")
    void backoffJitterNeverExceedsCap() {
        ReflectionTestUtils.setField(service, "backoffBase", Duration.ofMinutes(1));
        ReflectionTestUtils.setField(service, "backoffCap", Duration.ofMinutes(10));
        ReflectionTestUtils.setField(service, "backoffJitter", 0.9);

        // attempts 가 크면 nominal 이 이미 상한(10분)에서 잘린다. jitter 를 더해도 상한을 넘으면 안 된다.
        for (int i = 0; i < 100; i++) {
            assertThat(service.backoffDelay(30)).isLessThanOrEqualTo(Duration.ofMinutes(10));
        }
    }

    @Test
    @DisplayName("시뮬레이션: 막힌 건 다수가 같은 attempts 로 같은 초에 다시 몰리는 수를 지터 전후로 센다(#399)")
    void backoffJitterReducesSameSecondCollisions() {
        ReflectionTestUtils.setField(service, "backoffBase", Duration.ofMinutes(1));
        ReflectionTestUtils.setField(service, "backoffCap", Duration.ofMinutes(10));
        Instant now = Instant.parse("2026-09-28T00:00:00Z");
        int stuckCount = 200;
        int attempts = 2; // nominal 4분, 상한 밖이라 jitter 효과가 온전히 보인다

        ReflectionTestUtils.setField(service, "backoffJitter", 0.0);
        int worstBucketWithoutJitter = worstSameSecondBucket(now, attempts, stuckCount);
        assertThat(worstBucketWithoutJitter).isEqualTo(stuckCount); // 지터 0: 전부 같은 초

        ReflectionTestUtils.setField(service, "backoffJitter", 0.5);
        int worstBucketWithJitter = worstSameSecondBucket(now, attempts, stuckCount);
        System.out.println("[#399] 같은 초 재시도 몰림(최대 버킷): jitter=0 → " + worstBucketWithoutJitter
                + "/" + stuckCount + ", jitter=0.5 → " + worstBucketWithJitter + "/" + stuckCount);

        assertThat(worstBucketWithJitter).isLessThan(worstBucketWithoutJitter);
    }

    /** 막힌 건 {@code count}개가 전부 {@code attempts} 번째 시도라 할 때 다음 시각을 초 단위로 묶어 최대 버킷 크기를 센다. */
    private int worstSameSecondBucket(Instant now, int attempts, int count) {
        java.util.Map<Instant, Integer> bySecond = new java.util.HashMap<>();
        for (int i = 0; i < count; i++) {
            Instant next = now.plus(service.backoffDelay(attempts)).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
            bySecond.merge(next, 1, Integer::sum);
        }
        return bySecond.values().stream().max(Integer::compareTo).orElse(0);
    }

    @Test
    @DisplayName("oldest 는 오래된 순을 명시한 쿼리를 쓴다")
    void oldestUsesOrderedQuery() {
        ReflectionTestUtils.setField(service, "policy", "oldest");
        when(repository.findByStatusAndRequestedAtBeforeOrderByRequestedAtAsc(eq(PaymentStatus.UNKNOWN), any(Instant.class),
                any(Pageable.class))).thenReturn(List.of());

        service.recoverUnknownPayments();

        verify(repository).findByStatusAndRequestedAtBeforeOrderByRequestedAtAsc(eq(PaymentStatus.UNKNOWN), any(Instant.class),
                any(Pageable.class));
        verify(repository, never()).findByStatusAndRequestedAtBefore(any(), any(), any());
    }
}
