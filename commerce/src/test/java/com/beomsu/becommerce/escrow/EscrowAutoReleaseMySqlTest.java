package com.beomsu.becommerce.escrow;

import com.beomsu.becommerce.escrow.internal.EscrowHold;
import com.beomsu.becommerce.escrow.internal.EscrowHoldRepository;
import com.beomsu.becommerce.escrow.internal.EscrowStatus;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code autoReleaseDue} 가 릴리스 이벤트를 <b>리스너에게 실제로 전달</b>하는지 본다.
 *
 * <p>{@code autoReleaseDue} 가 같은 빈의 릴리스 메서드를 자기호출하면 프록시를 타지 않아
 * {@code @Transactional} 이 무시되고 {@link EscrowReleasedEvent} 가 트랜잭션 밖에서 발행돼
 * AFTER_COMMIT 리스너가 불리지 않는다(발행은 미완료로만 남는다). 목 단위 테스트는 이벤트 발행을
 * {@code verify} 로 보지만, 발행된 이벤트가 <b>커밋에 실려 리스너에 도달하는지</b>는 실 컨텍스트에서만
 * 드러난다. 여기서는 실제 Spring 컨텍스트 + 실 MySQL 로 그 전달을 확인한다.
 */
@Tag("integration")
@SpringBootTest
@Import(EscrowAutoReleaseMySqlTest.Listeners.class)
class EscrowAutoReleaseMySqlTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        SharedContainers.register(registry, "EscrowAutoRelease");
    }

    @Autowired EscrowService escrowService;
    @Autowired EscrowHoldRepository repository;

    static final AtomicInteger RELEASED_EVENTS = new AtomicInteger();

    /** 테스트 전용 리스너 — AFTER_COMMIT 이라 릴리스 트랜잭션이 커밋돼야 불린다. */
    static class Recorder {
        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        public void onReleased(EscrowReleasedEvent event) {
            RELEASED_EVENTS.incrementAndGet();
        }
    }

    @TestConfiguration
    static class Listeners {
        @Bean
        Recorder recorder() {
            return new Recorder();
        }
    }

    @BeforeEach
    void clean() {
        RELEASED_EVENTS.set(0);
        repository.deleteAll();
    }

    @Test
    @DisplayName("autoReleaseDue 는 릴리스 이벤트를 리스너에 실제로 전달한다(자기호출이면 불리지 않는다)")
    void autoReleaseDeliversEventToListener() {
        repository.save(EscrowHold.hold("ord-auto-1", 10_000,
                Instant.now().minusSeconds(3600), Instant.now().minusSeconds(60)));

        int released = escrowService.autoReleaseDue();

        assertThat(released).isEqualTo(1);
        assertThat(repository.findByOrderNo("ord-auto-1").orElseThrow().getStatus())
                .isEqualTo(EscrowStatus.RELEASED);
        assertThat(RELEASED_EVENTS.get())
                .as("이벤트가 트랜잭션 안에서 발행돼야 AFTER_COMMIT 리스너가 불린다")
                .isEqualTo(1);
    }
}
