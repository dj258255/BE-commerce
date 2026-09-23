package com.beomsu.becommerce.order.recovery;

import com.beomsu.becommerce.payment.va.VirtualAccountService;
import com.beomsu.becommerce.payment.recovery.PaymentRecoveryService;
import com.beomsu.becommerce.order.internal.OrderStatus;
import com.beomsu.becommerce.order.internal.OrderRepository;
import com.beomsu.becommerce.order.internal.Order;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 주문 만료 배치.
 *
 * <p>{@code Order.markExpired()} 로직은 있으나 이를 주기 호출하는 경로가 없어, PENDING_PAYMENT로
 * 유효시간(30분)이 지난 주문이 EXPIRED로 넘어가지 못하고 방치됐다. 이 서비스가 그런 주문을 스캔해
 * 만료시킨다. 건별 예외를 격리해 한 건 실패가 배치 전체를 멈추지 않게 하고(다음 주기 재시도),
 * 처리한 건수를 반환한다({@code VirtualAccountService.expireOverdue}·{@code PaymentRecoveryService}와 동일 패턴).
 */
@Service
@Transactional
@RequiredArgsConstructor
public class OrderExpiryService {

    private static final Logger log = LoggerFactory.getLogger(OrderExpiryService.class);

    private final OrderRepository orderRepository;

    /**
     * PENDING_PAYMENT이며 만료 예정 시각이 지난 주문을 EXPIRED로 전이한다. 반환값은 처리한 건수.
     */
    public int expireOverdue(Instant now) {
        List<Order> targets =
                orderRepository.findByStatusAndExpiresAtBefore(OrderStatus.PENDING_PAYMENT, now, chunk());

        int processed = 0;
        for (Order order : targets) {
            try {
                order.markExpired();
                // 상태 전이(EXPIRED)를 saveAndFlush로 명시 영속한다. dirty-check 자동 flush는 readOnly
                // 조회로 세션 FlushMode가 MANUAL이거나 detached 엔티티인 경우 신뢰할 수 없어(pay-26 교훈) 확정을 강제한다.
                orderRepository.saveAndFlush(order);
                processed++;
            } catch (Exception e) {
                // 한 건 실패가 배치 전체를 멈추지 않게 한다. 다음 주기에 다시 시도된다.
                log.warn("주문 만료 처리 실패 orderNo={} : {}", order.getOrderNo(), e.getMessage());
            }
        }
        return processed;
    }

    /**
     * 배치 한 번이 읽는 상한. 남은 것은 다음 주기가 가져간다.
     *
     * <p><b>필드에 기본값을 둔다.</b> {@code @Value} 는 스프링이 만들어 줄 때만 채워지는데,
     * 단위 테스트는 이 서비스를 직접 생성한다. 초기값이 없으면 0 이 되어 페이지 크기가
     * 0 이라고 터진다 — 실제로 그렇게 깨졌다.
     */
    @org.springframework.beans.factory.annotation.Value("${app.batch.read-chunk-size:500}")
    private int readChunkSize = 500;

    /**
     * <b>설정이 0 이나 음수여도 배치를 죽이지 않는다.</b> 잘못된 설정 하나로 돈을 다루는
     * 배치가 멈추는 것보다, 기본값으로 도는 편이 낫다.
     */
    private org.springframework.data.domain.Pageable chunk() {
        return org.springframework.data.domain.PageRequest.of(0, readChunkSize > 0 ? readChunkSize : 500);
    }
}
