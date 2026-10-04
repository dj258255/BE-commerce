package com.beomsu.becommerce.shared.outbox;

import com.beomsu.becommerce.testsupport.SharedContainers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link OutboxResubmitScheduler}가 <b>실제 Modulith 이벤트 발행 레지스트리</b>를 통해 재전달하는지 본다.
 *
 * <p><b>왜 목 테스트로는 부족한가</b>: {@code OutboxResubmitSchedulerTest}는 스케줄러가 넘긴
 * {@link java.util.function.Predicate}를 테스트가 만든 mock {@code EventPublication}에 적용해볼 뿐이다.
 * 그래서 predicate가 <b>잘못된 날짜 필드를 읽어도</b>(예: {@code publicationDate} 대신 다른 필드) 그 mock이
 * 기대대로 채워져 있으면 통과한다. 여기서는 실제 {@link org.springframework.modulith.events.EventPublication}
 * (JPA가 {@code event_publication}에서 읽어 온 값)으로 검증한다 — 실패한 리스너가 만든 미완료 발행이
 * 실제로 재전달돼 리스너가 다시 불리고, 정상 완료된 발행은 재전달되지 않음을 레지스트리 경유로 증명한다.
 *
 * <p>실 리스너를 하나 심는다: 첫 전달은 예외를 던져 발행을 미완료로 남기고, 두 번째(재제출)는 성공해
 * 완료로 닫는다. {@code @TransactionalEventListener(AFTER_COMMIT)}로 두어 재전달이 같은 스레드에서
 * 동기로 일어나게 한다(@Async면 타이밍이 흔들린다).
 */
@Tag("integration")
@SpringBootTest
@Import(OutboxResubmitMySqlTest.Listeners.class)
class OutboxResubmitMySqlTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        SharedContainers.register(registry, "OutboxResubmit");
        // 스케줄러 빈과 짝 게이트를 켠다(기본 off). 나이 문턱은 0으로 두고 즉시 재전달을 본다.
        registry.add("app.outbox.resubmit.enabled", () -> "true");
        registry.add("app.outbox.resubmit.min-age", () -> "PT0S");
        registry.add("app.outbox.resubmit.max-per-tick", () -> "100");
    }

    @Autowired OutboxResubmitScheduler scheduler;
    @Autowired ApplicationEventPublisher events;
    @Autowired PlatformTransactionManager txManager;
    @Autowired JdbcTemplate jdbc;

    static final AtomicInteger FLAKY_CALLS = new AtomicInteger();
    static final AtomicInteger OK_CALLS = new AtomicInteger();

    /** 테스트 전용 이벤트 두 종류 — 앱의 다른 리스너와 겹치지 않게 별도 타입으로 만든다. */
    record FlakyEvent(String id) {}
    record AlwaysOkEvent(String id) {}

    /** 첫 전달은 실패하고 재전달은 성공하는 리스너 + 항상 성공하는 리스너. */
    static class FlakyListener {
        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        public void onFlaky(FlakyEvent event) {
            if (FLAKY_CALLS.getAndIncrement() == 0) {
                throw new IllegalStateException("첫 전달 실패(의도) — 발행을 미완료로 남긴다");
            }
        }

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        public void onAlwaysOk(AlwaysOkEvent event) {
            OK_CALLS.incrementAndGet();
        }
    }

    @TestConfiguration
    static class Listeners {
        @Bean
        FlakyListener flakyListener() {
            return new FlakyListener();
        }
    }

    @BeforeEach
    void clean() {
        FLAKY_CALLS.set(0);
        OK_CALLS.set(0);
        // 이 클래스 전용 새 DB라 앱이 만든 발행은 없다 — 우리 발행만 지우면 된다.
        jdbc.update("delete from event_publication");
        jdbc.update("delete from event_publication_archive");
    }

    @Test
    @DisplayName("실패해 미완료가 된 발행은, min-age 를 넘긴 뒤 run() 이 리스너를 실제로 다시 호출하고 완료로 닫는다")
    void resubmitsIncompletePublicationThroughRegistry() {
        publish(new FlakyEvent("e1"));

        assertThat(incompleteCount()).as("첫 전달 실패 → 미완료 발행 1건").isEqualTo(1);
        assertThat(FLAKY_CALLS.get()).as("리스너는 아직 한 번 불렸다").isEqualTo(1);

        scheduler.run();

        assertThat(FLAKY_CALLS.get()).as("재제출로 리스너가 다시 불렸다").isEqualTo(2);
        assertThat(incompleteCount()).as("성공적으로 닫혀 미완료가 없다").isZero();
    }

    @Test
    @DisplayName("정상 완료된 발행은 재제출 대상이 아니다 — 리스너가 다시 불리지 않는다")
    void doesNotResubmitCompletedPublication() {
        publish(new AlwaysOkEvent("e2"));

        assertThat(incompleteCount()).as("첫 전달 성공 → 미완료 없음").isZero();
        assertThat(OK_CALLS.get()).isEqualTo(1);

        scheduler.run();

        assertThat(OK_CALLS.get()).as("완료분은 재전달되지 않는다").isEqualTo(1);
        assertThat(incompleteCount()).isZero();
    }

    /** 트랜잭션 안에서 발행한다(AFTER_COMMIT 리스너는 커밋돼야 돈다). 리스너의 실패는 커밋 후 전파되므로 삼킨다. */
    private void publish(Object event) {
        try {
            new TransactionTemplate(txManager).executeWithoutResult(status -> events.publishEvent(event));
        } catch (RuntimeException expectedFirstFailure) {
            // 의도된 첫 전달 실패 — 발행은 이미 커밋됐고 미완료로 남는다.
        }
    }

    private int incompleteCount() {
        Integer count = jdbc.queryForObject(
                "select count(*) from event_publication where completion_date is null", Integer.class);
        return count == null ? 0 : count;
    }
}
