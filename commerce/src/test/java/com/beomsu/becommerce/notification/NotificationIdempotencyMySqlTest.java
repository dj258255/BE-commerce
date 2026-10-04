package com.beomsu.becommerce.notification;

import com.beomsu.becommerce.notification.consumption.ProcessedEventRepository;
import com.beomsu.becommerce.payment.PaymentConfirmedEvent;
import com.beomsu.becommerce.testsupport.SharedContainers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 알림 소비 멱등을 실 MySQL 로 고정한다.
 *
 * <p>(1) 같은 이벤트가 <b>동시에 두 번</b> 배달돼도 알림은 한 번만 나간다 — 이력을 발송보다 먼저 같은
 * tx 에 적고 강제 flush 하므로 (eventKey, consumer) 유니크가 동시 insert 를 직렬화한다.
 * (2) 발송이 실패하면 이력도 함께 롤백돼 재전달이 다시 시도한다(at-least-once). 목 단위 테스트로는
 * 유니크 직렬화도 롤백도 재현할 수 없어(영속성 컨텍스트가 없다) 실 DB 로 확인한다.
 */
@Tag("integration")
@SpringBootTest
@Import(NotificationIdempotencyMySqlTest.SenderConfig.class)
class NotificationIdempotencyMySqlTest {

    /** 로그 sender 대신 발송 횟수를 세고, "다음 발송은 실패"를 주입할 수 있는 sender. */
    static class RecordingSender implements NotificationSender {
        final AtomicInteger sends = new AtomicInteger();
        final AtomicBoolean failNext = new AtomicBoolean(false);

        @Override
        public void sendPaymentReceipt(String orderNo, Long paymentId, long amount) {
            if (failNext.get()) {
                throw new RuntimeException("발송 채널 장애(의도)");
            }
            sends.incrementAndGet();
        }
    }

    @TestConfiguration
    static class SenderConfig {
        @Bean
        @Primary
        RecordingSender recordingSender() {
            return new RecordingSender();
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        SharedContainers.register(registry, "NotificationIdempotency");
    }

    @Autowired NotificationService service;
    @Autowired ProcessedEventRepository processedEvents;
    @Autowired RecordingSender sender;

    @BeforeEach
    void clean() {
        processedEvents.deleteAll();
        sender.sends.set(0);
        sender.failNext.set(false);
    }

    @Test
    @DisplayName("같은 이벤트 동시 2건 → 유니크가 직렬화해 발송 1회, 재전달은 조용히 끝난다")
    void concurrentDuplicatesSendOnce() throws Exception {
        PaymentConfirmedEvent event =
                new PaymentConfirmedEvent("ord-n-1", 999_001L, 10_000, Instant.now());

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicReference<Throwable> loserFailure = new AtomicReference<>();
        Callable<Void> attempt = () -> {
            start.await();
            try {
                service.handlePaymentConfirmed(event);
            } catch (RuntimeException ex) {
                loserFailure.set(ex);   // 진 쪽은 유니크 위반으로 롤백된다
            }
            return null;
        };
        Future<Void> first = pool.submit(attempt);
        Future<Void> second = pool.submit(attempt);
        start.countDown();
        first.get(30, TimeUnit.SECONDS);
        second.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 인터리빙 무관 불변식. 겹치면 한쪽이 유니크 위반으로 실패하고, 겹치지 않으면 둘째가 existsBy 로
        // 조용히 skip 한다 — 둘 다 정상 업무다. 그래서 "정확히 하나만 실패"를 강제하지 않고,
        // <b>예외가 났다면 그 타입은 유니크 위반 계열</b>이라는 것만 본다(예외 없음도 허용).
        Throwable failure = loserFailure.get();
        if (failure != null) {
            assertThat(failure)
                    .as("실패했다면 유니크 위반(DataIntegrityViolation) 계열이어야 한다")
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThat(sender.sends.get()).as("동시 중복이어도 발송은 정확히 1회").isEqualTo(1);
        assertThat(processedEvents.count()).as("이력도 정확히 1건").isEqualTo(1);

        // 재전달 시뮬레이션 — 같은 이벤트를 한 번 더. exists 검사에서 조용히 끝나고 발송은 여전히 1회다.
        service.handlePaymentConfirmed(event);

        assertThat(sender.sends.get()).as("재전달은 발송하지 않는다").isEqualTo(1);
        assertThat(processedEvents.existsByEventKeyAndConsumer("payment-confirmed-999001", "notification"))
                .isTrue();
        assertThat(processedEvents.count()).as("이력도 한 줄").isEqualTo(1);
    }

    @Test
    @DisplayName("발송 실패 → 이력 미잔존(롤백), 재전달에서 발송 성공 + 이력 1건")
    void sendFailureRollsBackThenRedeliverySucceeds() {
        PaymentConfirmedEvent event =
                new PaymentConfirmedEvent("ord-n-2", 999_002L, 10_000, Instant.now());

        sender.failNext.set(true);
        assertThatThrownBy(() -> service.handlePaymentConfirmed(event))
                .as("발송 실패는 삼키지 않고 전파한다(롤백·재전달)")
                .isInstanceOf(RuntimeException.class);
        assertThat(processedEvents.count())
                .as("발송 실패 → 이력 insert 도 함께 롤백돼 남지 않는다")
                .isZero();

        sender.failNext.set(false);
        service.handlePaymentConfirmed(event);   // 재전달

        assertThat(sender.sends.get()).as("재전달에서 정확히 1회 발송").isEqualTo(1);
        assertThat(processedEvents.existsByEventKeyAndConsumer("payment-confirmed-999002", "notification"))
                .isTrue();
        assertThat(processedEvents.count()).isEqualTo(1);
    }
}
