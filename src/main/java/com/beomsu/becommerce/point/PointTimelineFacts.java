package com.beomsu.becommerce.point;

import com.beomsu.becommerce.point.internal.PointHistoryRepository;
import com.beomsu.becommerce.order.internal.RefundAllocator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 포인트가 타임라인에 내주는 사실 (ADR-011). 지금까지 orderNo로 조회할 수단이 없었다.
 *
 * <p>부분취소 원인을 판단할 때 중요하다 — 포인트를 먼저 환불하는 정책이라
 * ({@code RefundAllocator}) 포인트 사건 순서가 곧 환불 순서다.
 */
@Service
public class PointTimelineFacts {

    private final PointHistoryRepository repository;

    PointTimelineFacts(PointHistoryRepository repository) {
        this.repository = repository;
    }

    /** 한 주문이 만든 포인트 사건 전부. 사용·복원·적립·회수가 시간순으로 들어 있다. */
    @Transactional(readOnly = true)
    public List<PointFact> findByOrderNo(String orderNo) {
        return repository.findByOrderNoOrderByIdAsc(orderNo).stream()
                .map(h -> new PointFact(h.getCreatedAt(), h.getType().name(), h.getAmount()))
                .toList();
    }

    public record PointFact(Instant at, String type, long amount) {
    }
}
