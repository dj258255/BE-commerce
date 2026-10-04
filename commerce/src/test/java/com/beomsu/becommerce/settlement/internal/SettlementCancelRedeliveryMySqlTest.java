package com.beomsu.becommerce.settlement.internal;

import com.beomsu.becommerce.payment.PaymentCanceledEvent;
import com.beomsu.becommerce.payment.PaymentConfirmedEvent;
import com.beomsu.becommerce.testsupport.SharedContainers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 정산의 취소 선도착 보류가 <b>실 Modulith 발행 레지스트리를 거쳐</b> 수렴하는지 본다.
 *
 * <p>{@code SettlementServiceTest} 는 목 리포지토리로 "1차 예외 → 2차 반영"을 흉내 내지만, 그건
 * 예외가 실제로 <b>발행을 미완료로 남기고 재전달이 그 발행을 다시 실행하는지</b>를 증명하지 않는다.
 * 여기서는 실 컨텍스트에서 승인보다 취소를 먼저 발행해 취소 리스너가 예외로 미완료가 되는 것을 확인하고,
 * 그 뒤 승인이 처리된 다음 <b>레지스트리 재제출({@link IncompleteEventPublications})</b>로 취소가 실제
 * 반영되는 것까지 본다({@code EscrowAutoReleaseMySqlTest}·{@code OutboxResubmitMySqlTest} 와 같은 구성).
 */
@Tag("integration")
@SpringBootTest
class SettlementCancelRedeliveryMySqlTest {

    private static final String ORDER = "ord-c1-redelivery";
    private static final long PAYMENT_ID = 987_654_321L;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        SharedContainers.register(registry, "SettlementCancelRedelivery");
    }

    @Autowired ApplicationEventPublisher events;
    @Autowired PlatformTransactionManager txManager;
    @Autowired SettlementItemRepository itemRepository;
    @Autowired IncompleteEventPublications incompleteEventPublications;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        itemRepository.deleteAll();
        jdbc.update("delete from event_publication");
        jdbc.update("delete from event_publication_archive");
    }

    @Test
    @DisplayName("취소 선도착 → 레지스트리 미완료로 남고, 승인 처리 뒤 재제출로 취소가 실제 반영된다")
    void cancelFirstDefersThenConvergesAfterConfirmViaRegistry() {
        // 1) 승인보다 취소를 먼저 발행한다. 정산 항목이 아직 없어 취소 리스너가 예외로 보류되고,
        //    그 리스너의 발행만 미완료로 남는다(다른 리스너와 독립).
        publish(new PaymentCanceledEvent(ORDER, PAYMENT_ID, 1, 10_000, 0, true, Instant.now()));

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(incompleteCount())
                        .as("취소 리스너가 항목 없음으로 예외 → 발행이 미완료로 남아야 한다")
                        .isGreaterThanOrEqualTo(1));
        assertThat(itemRepository.findByPaymentId(PAYMENT_ID)).isEmpty();

        // 2) 이제 승인이 처리돼 정산 항목이 적재된다(보류를 풀 상대 이벤트가 도착).
        publish(new PaymentConfirmedEvent(ORDER, PAYMENT_ID, 10_000, Instant.now()));
        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(itemRepository.findByPaymentId(PAYMENT_ID))
                        .as("승인 리스너가 항목을 적재해야 한다")
                        .isPresent());

        // 3) 미완료 발행을 레지스트리로 재제출한다 → 취소 리스너가 이번엔 항목을 만나 취소를 반영한다.
        incompleteEventPublications.resubmitIncompletePublications(p -> true);

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(itemRepository.findByPaymentId(PAYMENT_ID).orElseThrow().getStatus())
                        .as("재전달에서 취소가 실제로 반영돼야 한다")
                        .isEqualTo(SettlementItemStatus.CANCELED));
    }

    /** 트랜잭션 안에서 발행한다(리스너는 AFTER_COMMIT 이라 커밋돼야 돈다). */
    private void publish(Object event) {
        new TransactionTemplate(txManager).executeWithoutResult(status -> events.publishEvent(event));
    }

    private int incompleteCount() {
        Integer count = jdbc.queryForObject(
                "select count(*) from event_publication where completion_date is null", Integer.class);
        return count == null ? 0 : count;
    }
}
