package com.beomsu.becommerce.order.internal;

import com.beomsu.becommerce.order.recovery.CheckoutRecoveryService;
import com.beomsu.becommerce.payment.pg.FakePgClient;
import com.beomsu.becommerce.payment.pg.PgApproveResult;
import com.beomsu.becommerce.shared.Money;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** 재고 예약 통합 테스트(#374)가 같이 쓰는 도우미. 전략마다 스프링 설정이 달라 클래스를 나눈다. */
abstract class StockReservationTestSupport {

    private static final AtomicLong SEQ = new AtomicLong();

    @Autowired
    CheckoutService checkoutService;

    @Autowired
    CheckoutRecoveryService recoveryService;

    @Autowired
    OrderRepository orderRepository;

    @Autowired
    FakePgClient fakePg;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    com.beomsu.becommerce.payment.recovery.PaymentRecoveryService paymentRecoveryService;

    /** 결제 복구가 PG 조회로 확정한 뒤, 주문이 배치 없이 마무리될 때까지 최대 10초 기다린다(#378). */
    String awaitOrderStatus(String orderNo, String expected) throws InterruptedException {
        String status = null;
        for (int i = 0; i < 100; i++) {
            status = orderStatus(orderNo);
            if (expected.equals(status)) {
                return status;
            }
            Thread.sleep(100);
        }
        return status;
    }

    @AfterEach
    void resetPg() {
        fakePg.setNextResult(PgApproveResult.success("CARD"));
    }

    /** 이 테스트만 쓰는 상품. 가격 10,000원, 재고는 인자대로. */
    long product(int quantity) {
        long id = 90_374_000L + SEQ.incrementAndGet() + (System.nanoTime() % 1000) * 1000;
        jdbc.update("INSERT INTO products (product_id, name, price) VALUES (?, ?, 10000)", id, "예약 실험 " + id);
        jdbc.update("INSERT INTO stock (product_id, quantity, version) VALUES (?, ?, 0)", id, quantity);
        return id;
    }

    long user() {
        return 7_374_000L + SEQ.incrementAndGet();
    }

    String order(long userId, long productId) {
        return checkoutService.createOrder(userId, List.of(new OrderLine(productId, 1))).orderNo();
    }

    CheckoutResult pay(String orderNo, long userId, String paymentKey) {
        return checkoutService.confirm(orderNo, paymentKey, Money.krw(10_000), 0, 0, userId);
    }

    void recover(String orderNo) {
        recoveryService.resolveNow(orderRepository.findByOrderNo(orderNo).orElseThrow());
    }

    int stock(long productId) {
        return jdbc.queryForObject("SELECT quantity FROM stock WHERE product_id = ?", Integer.class, productId);
    }

    String orderStatus(String orderNo) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE order_no = ?", String.class, orderNo);
    }

    List<String> reservations(String orderNo) {
        return jdbc.queryForList("SELECT status FROM stock_reservations WHERE order_no = ?", String.class, orderNo);
    }

    int payments(String orderNo) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM payments WHERE order_no = ?", Integer.class, orderNo);
    }

    int netCancels(String orderNo) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM compensation_tasks WHERE order_no = ?", Integer.class, orderNo);
    }
}
