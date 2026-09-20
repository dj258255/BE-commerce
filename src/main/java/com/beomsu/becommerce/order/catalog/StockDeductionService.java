package com.beomsu.becommerce.order.catalog;

import com.beomsu.becommerce.order.internal.OrderException;
import jakarta.persistence.OptimisticLockException;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 재고 차감 전략 3종 — Phase 5 동시성 비교 실험의 대상.
 *
 * <p>세 방식 모두 "재고가 음수가 되지 않는다"는 정합성은 지킨다. 차이는 성능·경합 처리 방식이다.
 * 부하테스트로 TPS·에러율을 비교해 선택 근거를 남긴다(ADR-004).
 * <ul>
 *   <li><b>비관적 락</b>: SELECT FOR UPDATE. 충돌 잦을 때 확실하지만 커넥션을 오래 점유한다.</li>
 *   <li><b>낙관적 락</b>: @Version + 재시도. 충돌 드물 때 유리, 잦으면 재시도 폭증.</li>
 *   <li><b>조건부 UPDATE</b>: 락 없이 원자적. 대개 가장 저비용 — 이 프로젝트의 기본 선택.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class StockDeductionService {

    private final StockRepository stockRepository;

    /**
     * <b>재시도가 관측을 지운다.</b> 낙관적 락 충돌은 재시도가 흡수하므로, 경합이 있어도 끝내
     * 성공하면 로그도 지표도 아무것도 안 남는다. 최종 결과만 보는 관측은 <b>네 번 만에 성공한
     * 요청을 건강한 요청으로</b> 읽고, 그 사이 늘어난 지연은 응답 시간 안에 묻힌다.
     *
     * <p>그래서 <b>논리적 호출과 실제 시도를 나눠 센다.</b> 이 카운터가 없으면 "경합이 있느냐"에
     * 답할 방법이 없다 — 실패했을 때만 알게 되고, 그때는 이미 상한을 소진한 뒤다.
     */
    private final MeterRegistry meterRegistry;

    /** 조건부 UPDATE — 기본 전략. 영향 행 0이면 재고 부족. */
    @Transactional
    public void deductConditional(long productId, int qty) {
        int updated = stockRepository.deductConditionally(productId, qty);
        if (updated == 0) {
            throw OrderException.outOfStock(productId);
        }
    }

    /**
     * 예외 없는 조건부 차감 — 성공 true, 재고부족 false.
     *
     * <p>승인 후 보상 경로(재고 부족 시 자동 망취소)에서 트랜잭션 오염을 피하려고 예외 대신 boolean을 쓴다.
     * {@link OrderException} 같은 RuntimeException을 던지면 잡아도 트랜잭션이 rollback-only로 오염돼
     * 승인·보상태스크 적재까지 함께 롤백되기 때문이다.
     */
    @Transactional
    public boolean tryDeduct(long productId, int qty) {
        return stockRepository.deductConditionally(productId, qty) > 0;
    }

    /** 전액 취소 시 재고 복원 — 차감했던 수량을 되돌린다. */
    @Transactional
    public void restore(long productId, int qty) {
        if (qty <= 0) {
            throw new OrderException("INVALID_REQUEST", "복원 수량은 1 이상이어야 합니다: " + qty);
        }
        stockRepository.restore(productId, qty);
    }

    /** 비관적 락 — 행을 잠그고 차감. */
    @Transactional
    public void deductPessimistic(long productId, int qty) {
        Stock stock = stockRepository.findByIdForUpdate(productId)
                .orElseThrow(() -> OrderException.outOfStock(productId));
        stock.deduct(qty);
    }

    private static final int MAX_RETRY = 10;

    /**
     * 낙관적 락 — @Version 충돌 시 재시도. 각 시도의 조회·저장은 별도 트랜잭션이며, 버전 충돌은
     * saveAndFlush 시점에 감지된다. 재시도를 다 소진하면 예외 — 고경합에서는 재시도 폭증으로
     * 정상 차감도 실패할 수 있다(낙관적 락의 특성). 자기호출 프록시 우회를 피하려고 루프를 인라인한다.
     */
    public void deductOptimisticWithRetry(long productId, int qty) {
        int attempts = 0;
        while (true) {
            try {
                Stock stock = stockRepository.findById(productId)
                        .orElseThrow(() -> OrderException.outOfStock(productId));
                stock.deduct(qty);
                stockRepository.saveAndFlush(stock); // 버전 충돌을 이 시점에 감지
                return;
            } catch (ObjectOptimisticLockingFailureException | OptimisticLockException e) {
                // 흡수하기 <전에> 센다. 성공으로 끝나도 경합이 있었다는 사실은 남아야 한다.
                meterRegistry.counter("stock.deduct.retry", "strategy", "optimistic").increment();
                if (++attempts >= MAX_RETRY) {
                    meterRegistry.counter("stock.deduct.exhausted", "strategy", "optimistic").increment();
                    throw new OrderException("STOCK_CONCURRENCY", "재고 차감 경합이 계속됩니다: " + productId);
                }
            }
        }
    }
}
