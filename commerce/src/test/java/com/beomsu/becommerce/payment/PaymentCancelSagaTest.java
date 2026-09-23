package com.beomsu.becommerce.payment;

import com.beomsu.becommerce.payment.internal.PaymentRepository;
import com.beomsu.becommerce.payment.internal.PaymentCancelTx;
import com.beomsu.becommerce.payment.pg.PgCancelCommand;
import com.beomsu.becommerce.payment.pg.PgCancelResult;
import com.beomsu.becommerce.payment.pg.PgClient;
import com.beomsu.becommerce.shared.Money;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 취소 사가의 <b>단계 순서</b>를 고정하는 회귀 테스트.
 *
 * <p>지키려는 성질은 하나다 — <b>PG 취소 호출이 두 트랜잭션 사이에서 일어난다.</b> 외부 호출이
 * 트랜잭션 안으로 다시 들어가면 느린 PG가 DB 커넥션을 붙잡아 폭주 시 풀이 마르고, PG 취소 성공 후
 * 커밋이 실패하면 롤백이 오히려 불일치를 만든다(PG는 취소됐는데 우리 장부는 안 취소됨).
 *
 * <p>이 성질은 코드를 읽어야만 보이고 기능 테스트로는 드러나지 않아, 순서 자체를 테스트로 박아 둔다.
 */
class PaymentCancelSagaTest {

    private PaymentRepository repository;
    private PgClient pg;
    private PaymentCancelTx cancelTx;
    private PaymentService service;

    @BeforeEach
    void setUp() {
        repository = mock(PaymentRepository.class);
        pg = mock(PgClient.class);
        cancelTx = mock(PaymentCancelTx.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        MeterRegistry meterRegistry = new SimpleMeterRegistry();
        service = new PaymentService(repository, pg, cancelTx, events, meterRegistry);
        // PG 취소는 거래키를 준다. 오케스트레이터가 그 키를 3단계로 넘겨 이력에 남기므로,
        // 스텁이 null을 주면 실제 경로와 다른 것을 검증하게 된다.
        lenient().when(pg.cancel(any(PgCancelCommand.class)))
                .thenReturn(new PgCancelResult("pg-tx-1"));
    }

    @Test
    @DisplayName("주문번호 취소: 대상 확정(tx) → PG 취소(tx 밖) → 결과 반영(tx) 순서로 실행된다")
    void cancelByOrderNoRunsThreePhasesInOrder() {
        when(cancelTx.resolveByOrderNo("order-9", Money.krw(5_000)))
                .thenReturn(new PaymentCancelTx.CancelTarget("pk-9", 1, null));

        service.cancelByOrderNo("order-9", Money.krw(5_000), "고객변심");

        InOrder ordered = inOrder(cancelTx, pg);
        ordered.verify(cancelTx).resolveByOrderNo("order-9", Money.krw(5_000));
        ordered.verify(pg).cancel(new PgCancelCommand("pk-9", 5_000, "고객변심", 1));
        ordered.verify(cancelTx).apply("pk-9", 1, Money.krw(5_000), "고객변심", "pg-tx-1");
    }

    @Test
    @DisplayName("결제 식별자 취소도 같은 3단계를 지킨다")
    void cancelByIdRunsThreePhasesInOrder() {
        when(cancelTx.resolveById(7L, Money.krw(3_000)))
                .thenReturn(new PaymentCancelTx.CancelTarget("pk-7", 1, null));

        service.cancel(7L, Money.krw(3_000), "부분 변심");

        InOrder ordered = inOrder(cancelTx, pg);
        ordered.verify(cancelTx).resolveById(7L, Money.krw(3_000));
        ordered.verify(pg).cancel(new PgCancelCommand("pk-7", 3_000, "부분 변심", 1));
        ordered.verify(cancelTx).apply("pk-7", 1, Money.krw(3_000), "부분 변심", "pg-tx-1");
    }

    @Test
    @DisplayName("취소 불가 결제면 PG를 부르지 않는다 — 1단계에서 걸러 헛된 외부 호출을 막는다")
    void doesNotCallPgWhenNotCancelable() {
        when(cancelTx.resolveByOrderNo(anyString(), any(Money.class)))
                .thenThrow(new PaymentException("CANCEL_AMOUNT_EXCEEDED", "취소 가능 금액을 초과했습니다."));

        assertThatThrownBy(() -> service.cancelByOrderNo("order-9", Money.krw(99_000), "과다취소"))
                .isInstanceOf(PaymentException.class);

        verify(pg, never()).cancel(any(PgCancelCommand.class));
        verify(cancelTx, never()).apply(anyString(), anyInt(), any(Money.class), anyString(), anyString());
    }

    @Test
    @DisplayName("PG 취소가 실패하면 결과 반영을 하지 않는다 — 우리만 취소로 남지 않게")
    void doesNotApplyWhenPgCancelFails() {
        when(cancelTx.resolveByOrderNo("order-9", Money.krw(5_000)))
                .thenReturn(new PaymentCancelTx.CancelTarget("pk-9", 1, null));
        when(pg.cancel(new PgCancelCommand("pk-9", 5_000, "고객변심", 1)))
                .thenThrow(new IllegalStateException("PG 취소 실패"));

        assertThatThrownBy(() -> service.cancelByOrderNo("order-9", Money.krw(5_000), "고객변심"))
                .isInstanceOf(IllegalStateException.class);

        verify(cancelTx, never()).apply(anyString(), anyInt(), any(Money.class), anyString(), anyString());
    }
}
