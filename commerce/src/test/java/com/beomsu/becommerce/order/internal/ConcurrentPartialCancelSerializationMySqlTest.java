package com.beomsu.becommerce.order.internal;

import com.beomsu.becommerce.point.PointService;
import com.beomsu.becommerce.testsupport.SharedContainers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 같은 주문의 <b>부분취소 2건이 동시에</b> 들어올 때 이중 환불이 나지 않는지 실 MySQL 로 본다.
 *
 * <p>부분취소는 Order 상태를 바꾸지 않아 저장이 없고, 그러면 {@code @Version} 직렬화가 걸리지 않는다.
 * 포인트·월렛 환불은 부분취소가 여러 번 가능해 비멱등이라, 겹치면 둘 다 환불해 이중 환불이 된다.
 * {@link CancelTx#settle} 이 환불 전에 Order 버전을 올려 명시 영속하므로, 늦게 flush 하는 쪽이 낙관
 * 충돌로 환불째 롤백돼야 한다.
 *
 * <p><b>단언은 인터리빙에 의존하지 않는다.</b> 두 취소가 겹치면 한쪽만 성공하고(직렬화), 순차로
 * 겹치지 않게 실행되면 둘 다 성공하는 것이 정상 업무다. 그래서 "정확히 하나만 성공"이 아니라
 * <b>환불 이력 수 = 성공한 취소 수</b>라는 불변식으로 단언하고, 겹침 확률을 높이려 반복한다.
 * 버그(환불이 성공 수보다 많이 남는 것)만 실패하게 한다.
 */
@Tag("integration")
@SpringBootTest
class ConcurrentPartialCancelSerializationMySqlTest extends StockReservationTestSupport {

    private static final long POINT_SEED = 10_000;
    private static final long POINT_USED = 6_000;
    private static final int ROUNDS = 5;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        SharedContainers.register(registry, "ConcurrentPartialCancel");
    }

    @Autowired PointService pointService;
    @Autowired CancelService cancelService;

    @Test
    @DisplayName("포인트 부분취소 2건 동시 — 어느 인터리빙에서도 환불 이력 수 = 성공 취소 수")
    void concurrentPointPartialCancelsKeepRefundCountInSync() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            long userId = user();
            String orderNo = paidPointOnlyOrder(userId, product(1), POINT_USED);

            int successes = runTwoConcurrentCancels(orderNo, userId, 3_000);

            assertThat(refundCount(orderNo))
                    .as("round %d: 포인트 환불 이력 수는 성공한 취소 수와 같아야 한다 — 많으면 이중 환불", round)
                    .isEqualTo(successes);
            assertThat(pointService.refundableAmount(orderNo))
                    .as("round %d: 남은 환불가능액 = 쓴 포인트 − 성공 수×3,000", round)
                    .isEqualTo(POINT_USED - successes * 3_000L);
            assertThat(statusOf(orderNo)).as("부분취소는 주문 상태를 바꾸지 않는다").isEqualTo("PAID");
        }
    }

    @Test
    @DisplayName("카드+포인트 복합결제 부분취소 2건 동시 — 내부 환불과 결제 반영(cancelCount)의 쌍이 맞는다")
    void concurrentMixedPartialCancelsKeepRefundAndPaymentInSync() throws Exception {
        long userId = user();
        long productId = product(1);
        String orderNo = order(userId, productId);
        pay(orderNo, userId, "pk-" + UUID.randomUUID());          // 카드 10,000 승인 → PAID
        pointService.earn(userId, POINT_SEED, "seed-" + orderNo);
        pointService.use(userId, POINT_USED, orderNo);             // 포인트 6,000 사용분
        // 8,000 취소 → 포인트 우선 6,000 + 카드 2,000 (부분취소).
        int successes = runTwoConcurrentCancels(orderNo, userId, 8_000);

        assertThat(refundCount(orderNo))
                .as("포인트 환불 이력 수 = 성공한 취소 수(이중 환불이면 많아진다)")
                .isEqualTo(successes);
        assertThat(pointService.refundableAmount(orderNo))
                .as("남은 포인트 환불가능액 = max(0, 6,000 − 성공 수×6,000)")
                .isEqualTo(Math.max(0L, POINT_USED - successes * POINT_USED));
        assertThat(paymentCancelCount(orderNo))
                .as("결제 취소 반영(cancelCount)도 성공한 취소 수와 쌍을 이룬다")
                .isEqualTo(successes);
    }

    /** 포인트만으로 환불되는(카드 결제 없는) PAID 주문 하나. {@code usedPoints} 만큼 포인트를 쓴 것으로 만든다. */
    private String paidPointOnlyOrder(long userId, long productId, long usedPoints) {
        String orderNo = order(userId, productId);
        Order order = orderRepository.findByOrderNo(orderNo).orElseThrow();
        order.startPayment();
        order.markPaid();
        orderRepository.saveAndFlush(order);
        pointService.earn(userId, POINT_SEED, "seed-" + orderNo);
        pointService.use(userId, usedPoints, orderNo);
        return orderNo;
    }

    /** 같은 취소를 두 스레드가 latch 로 동시에 시작한다. 반환값은 성공한 취소 수. */
    private int runTwoConcurrentCancels(String orderNo, long userId, long amount) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Callable<Boolean> attempt = () -> {
            start.await();
            try {
                cancelService.cancel(orderNo, amount, "동시부분취소", userId);
                return true;
            } catch (RuntimeException e) {
                return false;
            }
        };
        Future<Boolean> first = pool.submit(attempt);
        Future<Boolean> second = pool.submit(attempt);
        start.countDown();
        boolean ok1 = first.get(30, TimeUnit.SECONDS);
        boolean ok2 = second.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        return (ok1 ? 1 : 0) + (ok2 ? 1 : 0);
    }

    private int refundCount(String orderNo) {
        return jdbc.queryForObject(
                "select count(*) from point_histories where order_no = ? and type = 'REFUND'",
                Integer.class, orderNo);
    }

    private int paymentCancelCount(String orderNo) {
        Integer max = jdbc.queryForObject(
                "select coalesce(max(cancel_count), 0) from payments where order_no = ?",
                Integer.class, orderNo);
        return max == null ? 0 : max;
    }

    private String statusOf(String orderNo) {
        return jdbc.queryForObject("select status from orders where order_no = ?", String.class, orderNo);
    }
}
