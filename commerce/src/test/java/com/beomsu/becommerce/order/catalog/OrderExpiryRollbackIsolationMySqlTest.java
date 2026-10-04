package com.beomsu.becommerce.order.catalog;

import com.beomsu.becommerce.order.internal.Order;
import com.beomsu.becommerce.order.internal.OrderItem;
import com.beomsu.becommerce.order.internal.OrderRepository;
import com.beomsu.becommerce.order.recovery.OrderExpiryService;
import com.beomsu.becommerce.testsupport.SharedContainers;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 만료 배치에서 한 건이 예외로 실패해도 <b>나머지 건의 만료가 커밋</b>되는지 실 MySQL 로 본다.
 *
 * <p>{@code OrderExpiryService} 가 클래스 레벨 {@code @Transactional} 이면, 루프 안에서 예외를 잡아도
 * 그 예외가 <b>트랜잭션 참여 빈(@Transactional 저장소·서비스)에서 나온 것</b>이면 공유 트랜잭션이
 * rollback-only 로 오염돼 배치 전체가 롤백된다. 여기서는 릴리스에서 실패하는 주문 하나를 만들어 그
 * 사실을 재현한다 — 실패 건은 PENDING_PAYMENT 로 남고, 정상 건은 EXPIRED 로 커밋돼야 한다.
 *
 * <p>{@code StockReservationRepository} 가 package-private 이라 이 패키지(order.catalog)에 둔다 —
 * 실패를 주입하려면 실제 {@link StockReservationService} 를 상속해 그 생성자를 써야 한다.
 */
@Tag("integration")
@SpringBootTest
@Import(OrderExpiryRollbackIsolationMySqlTest.FailingRelease.class)
class OrderExpiryRollbackIsolationMySqlTest {

    private static final String FAIL_ORDER = "ord-expire-fail";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        SharedContainers.register(registry, "OrderExpiryRollback");
    }

    @Autowired OrderExpiryService orderExpiryService;
    @Autowired OrderRepository orderRepository;
    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("릴리스에서 한 건이 실패해도 정상 건의 만료는 커밋된다 — 배치가 통째로 롤백되지 않는다")
    void oneFailureDoesNotRollBackWholeBatch() {
        seedExpired(FAIL_ORDER);      // release 에서 실패할 주문
        seedExpired("ord-expire-ok"); // 정상 만료될 주문

        int processed = orderExpiryService.expireOverdue(Instant.now());

        assertThat(processed).as("정상 건 1건만 처리로 센다").isEqualTo(1);
        assertThat(statusOf("ord-expire-ok"))
                .as("실패 건이 배치 트랜잭션을 오염시켜도 정상 건은 커밋돼야 한다")
                .isEqualTo("EXPIRED");
        assertThat(statusOf(FAIL_ORDER))
                .as("실패 건은 다음 주기에 다시 시도되도록 PENDING_PAYMENT 로 남는다")
                .isEqualTo("PENDING_PAYMENT");
    }

    /** 만료 대상 주문 하나 — 고정 orderNo 로 심고 만료 시각을 과거로 둔다. */
    private void seedExpired(String orderNo) {
        Order order = Order.create(1L, List.of(OrderItem.of(1L, "상품", 10_000, 1)));
        orderRepository.saveAndFlush(order);
        jdbc.update("update orders set order_no = ?, expires_at = '2000-01-01 00:00:00' where id = ?",
                orderNo, order.getId());
    }

    private String statusOf(String orderNo) {
        return jdbc.queryForObject("select status from orders where order_no = ?", String.class, orderNo);
    }

    /**
     * 릴리스가 실패하는 주문을 하나 만든다. <b>실제 {@link StockReservationService} 를 상속</b>해
     * 메서드가 스프링 트랜잭션 프록시를 그대로 타게 한다 — 목은 프록시가 아니라 이 경로를 재현하지 못한다.
     */
    @TestConfiguration
    static class FailingRelease {
        @Bean
        @Primary
        StockReservationService failingRelease(StockReservationRepository reservations, StockRepository stock,
                                               MeterRegistry registry) {
            return new StockReservationService(reservations, stock, registry,
                    StockReservationService.Strategy.CHECK) {
                @Override
                public int release(String orderNo, String reason) {
                    if (FAIL_ORDER.equals(orderNo)) {
                        throw new IllegalStateException("release 실패(의도)");
                    }
                    return super.release(orderNo, reason);
                }
            };
        }
    }
}
