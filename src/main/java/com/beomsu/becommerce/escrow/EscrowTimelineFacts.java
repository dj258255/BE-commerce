package com.beomsu.becommerce.escrow;

import com.beomsu.becommerce.escrow.internal.EscrowHoldRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/** 에스크로가 타임라인에 내주는 사실 (ADR-011). 지금까지 이 정보를 볼 조회 API가 없었다. */
@Service
public class EscrowTimelineFacts {

    private final EscrowHoldRepository repository;

    EscrowTimelineFacts(EscrowHoldRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Optional<EscrowFact> findByOrderNo(String orderNo) {
        return repository.findByOrderNo(orderNo)
                .map(h -> new EscrowFact(h.getOrderNo(), h.getAmount(), h.getStatus().name(),
                        h.getHeldAt(), h.getAutoReleaseAt(), h.getResolvedAt()));
    }

    /** 보류·자동해제예정·해제 세 시점을 함께 준다 — 자금이 언제까지 묶여 있었는지가 대사의 재료다. */
    public record EscrowFact(String orderNo, long amount, String status,
                             Instant heldAt, Instant autoReleaseAt, Instant resolvedAt) {
    }
}
