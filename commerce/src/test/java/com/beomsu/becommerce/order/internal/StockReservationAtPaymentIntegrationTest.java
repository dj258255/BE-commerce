package com.beomsu.becommerce.order.internal;

import com.beomsu.becommerce.payment.PaymentStatus;
import com.beomsu.becommerce.payment.pg.PgApproveResult;
import com.beomsu.becommerce.testsupport.SharedContainers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 결제 시작에 재고를 잡는 전략(AT_PAYMENT, #374)의 상태 전이와 초과 판매를 실 MySQL 로 확인한다. */
@Tag("integration")
@SpringBootTest(properties = {
        "app.stock.reservation=AT_PAYMENT",
        "payment.fake-pg.timeout-approved-prefix=unk-ok-",
        "payment.fake-pg.timeout-lost-prefix=unk-lost-"})
@DisplayName("재고 예약 AT_PAYMENT — 결제 시작에 잡고 승인에 확정, 거절 · PG 에 없음이면 되돌린다")
class StockReservationAtPaymentIntegrationTest extends StockReservationTestSupport {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry props) {
        SharedContainers.register(props, "StockResAtPayment");
    }

    @Test
    @DisplayName("승인: 결제 시작에 재고가 빠지고 승인에 예약이 확정된다(한 번만 빠진다)")
    void approvedClaims() {
        long p = product(3);
        long u = user();
        String o = order(u, p);
        assertThat(stock(p)).isEqualTo(3);   // 주문 생성은 잡지 않는다

        CheckoutResult r = pay(o, u, "pk-" + UUID.randomUUID());

        assertThat(r.orderStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(stock(p)).isEqualTo(2);
        assertThat(reservations(o)).containsExactly("CLAIMED");
    }

    @Test
    @DisplayName("거절: 잡은 재고를 되돌리고, 같은 주문을 다시 결제하면 다시 잡아 확정한다")
    void declinedReleasesThenRetryReReserves() {
        long p = product(3);
        long u = user();
        String o = order(u, p);
        fakePg.setNextResult(PgApproveResult.failed("카드 거절"));

        CheckoutResult declined = pay(o, u, "pk-" + UUID.randomUUID());
        assertThat(declined.orderStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(stock(p)).isEqualTo(3);
        assertThat(reservations(o)).containsExactly("RELEASED");

        fakePg.setNextResult(PgApproveResult.success("CARD"));
        CheckoutResult retried = pay(o, u, "pk-" + UUID.randomUUID());
        assertThat(retried.orderStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(stock(p)).isEqualTo(2);
        assertThat(reservations(o)).containsExactly("CLAIMED");
    }

    @Test
    @DisplayName("매진: PG 를 부르기 전에 OUT_OF_STOCK, 주문 · 결제 · 재고가 그대로다")
    void soldOutFailsBeforePg() {
        long p = product(1);
        long u1 = user();
        long u2 = user();
        String first = order(u1, p);
        String second = order(u2, p);
        pay(first, u1, "pk-" + UUID.randomUUID());

        assertThatThrownBy(() -> pay(second, u2, "pk-" + UUID.randomUUID()))
                .isInstanceOf(OrderException.class)
                .satisfies(e -> assertThat(((OrderException) e).code()).isEqualTo("OUT_OF_STOCK"));

        assertThat(orderStatus(second)).isEqualTo("PENDING_PAYMENT");
        assertThat(payments(second)).isZero();
        assertThat(netCancels(second)).isZero();
        assertThat(stock(p)).isZero();
    }

    @Test
    @DisplayName("결과 모름 → PG 에 승인: 잡은 채 기다렸다가 복구가 확정한다(망취소 0)")
    void unknownApprovedStaysReservedThenClaims() {
        long p = product(1);
        long u = user();
        String o = order(u, p);

        CheckoutResult r = pay(o, u, "unk-ok-" + UUID.randomUUID());
        assertThat(r.paymentStatus()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(stock(p)).isZero();   // 결과를 모르는 동안 잡혀 있다
        assertThat(reservations(o)).containsExactly("RESERVED");

        recover(o);
        assertThat(orderStatus(o)).isEqualTo("PAID");
        assertThat(reservations(o)).containsExactly("CLAIMED");
        assertThat(netCancels(o)).isZero();
    }

    @Test
    @DisplayName("결과 모름 → PG 에 없음: 복구가 거절로 확정하며 재고를 되돌린다")
    void unknownLostReleases() {
        long p = product(1);
        long u = user();
        String o = order(u, p);

        pay(o, u, "unk-lost-" + UUID.randomUUID());
        assertThat(stock(p)).isZero();

        recover(o);
        assertThat(orderStatus(o)).isEqualTo("PENDING_PAYMENT");
        assertThat(reservations(o)).containsExactly("RELEASED");
        assertThat(stock(p)).isEqualTo(1);
    }

    @Test
    @DisplayName("동시 결제 20건, 재고 5: 정확히 5건만 PAID, 재고 0, 망취소 0, 초과 판매 0")
    void concurrentCheckoutsNeverOversell() throws Exception {
        long p = product(5);
        int buyers = 20;
        List<long[]> users = new ArrayList<>();
        List<String> orders = new ArrayList<>();
        for (int i = 0; i < buyers; i++) {
            long u = user();
            users.add(new long[]{u});
            orders.add(order(u, p));
        }
        AtomicInteger paid = new AtomicInteger();
        AtomicInteger soldOut = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(buyers);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < buyers; i++) {
            int k = i;
            futures.add(pool.submit(() -> {
                try {
                    if (pay(orders.get(k), users.get(k)[0], "pk-" + UUID.randomUUID()).orderStatus() == OrderStatus.PAID) {
                        paid.incrementAndGet();
                    }
                } catch (OrderException e) {
                    if ("OUT_OF_STOCK".equals(e.code())) {
                        soldOut.incrementAndGet();
                    } else {
                        throw e;
                    }
                }
                return null;
            }));
        }
        for (Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        int cancels = orders.stream().mapToInt(this::netCancels).sum();
        System.out.printf("%n  AT_PAYMENT 동시 %d건 · 재고 5 → PAID %d, 매진 %d, 재고 %d, 망취소 %d%n",
                buyers, paid.get(), soldOut.get(), stock(p), cancels);
        assertThat(paid.get()).isEqualTo(5);
        assertThat(soldOut.get()).isEqualTo(buyers - 5);
        assertThat(stock(p)).isZero();
        assertThat(cancels).isZero();
    }

    @Test
    @DisplayName("결제 복구가 PG 에 없음으로 확정하면 주문이 배치를 기다리지 않고 되돌아가고 재고가 풀린다(#378)")
    void recoveryReleasesWithoutWaitingForBatch() throws Exception {
        long p = product(1);
        long u = user();
        String o = order(u, p);
        String key = "unk-lost-" + UUID.randomUUID();
        pay(o, u, key);
        assertThat(stock(p)).isZero();

        paymentRecoveryService.resolveByPaymentKey(key);   // 웹훅 · 결제 복구 배치가 타는 경로

        assertThat(awaitOrderStatus(o, "PENDING_PAYMENT")).isEqualTo("PENDING_PAYMENT");
        assertThat(reservations(o)).containsExactly("RELEASED");
        assertThat(stock(p)).isEqualTo(1);
    }

    @Test
    @DisplayName("결제 복구가 승인으로 확정하면 주문이 배치를 기다리지 않고 PAID 가 된다(#378)")
    void recoveryClaimsWithoutWaitingForBatch() throws Exception {
        long p = product(1);
        long u = user();
        String o = order(u, p);
        String key = "unk-ok-" + UUID.randomUUID();
        pay(o, u, key);

        paymentRecoveryService.resolveByPaymentKey(key);

        assertThat(awaitOrderStatus(o, "PAID")).isEqualTo("PAID");
        assertThat(reservations(o)).containsExactly("CLAIMED");
        assertThat(stock(p)).isZero();
        assertThat(netCancels(o)).isZero();
    }
}
