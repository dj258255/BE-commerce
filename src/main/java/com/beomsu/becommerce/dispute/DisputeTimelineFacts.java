package com.beomsu.becommerce.dispute;

import com.beomsu.becommerce.dispute.internal.DisputeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/** 분쟁이 타임라인에 내주는 사실 (ADR-011). 한 주문에 분쟁이 여러 번 걸릴 수 있어 목록이다. */
@Service
public class DisputeTimelineFacts {

    private final DisputeRepository repository;

    DisputeTimelineFacts(DisputeRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<DisputeFact> findByOrderNo(String orderNo) {
        return repository.findByOrderNoOrderByIdAsc(orderNo).stream()
                .map(d -> new DisputeFact(d.getCreatedAt(), d.getResolvedAt(), d.getChargebackId(),
                        d.getStatus().name(), d.getAmount(), d.getReason()))
                .toList();
    }

    public record DisputeFact(Instant openedAt, Instant resolvedAt, String chargebackId,
                              String status, long amount, String reason) {
    }
}
