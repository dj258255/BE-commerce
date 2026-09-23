package com.beomsu.becommerce.settlement;

import com.beomsu.becommerce.settlement.internal.SettlementItemRepository;
import com.beomsu.becommerce.settlement.internal.Settlement;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 정산이 타임라인에 내주는 사실 (ADR-011).
 *
 * <p>대사 불일치에서 <b>수수료 차이</b>가 가장 흔한 원인 후보인데, 그 수수료를 정한 것이
 * 여기다. 정산 항목의 수수료를 볼 수 없으면 그 원인을 확인할 방법이 없다.
 */
@Service
public class SettlementTimelineFacts {

    private final SettlementItemRepository repository;

    SettlementTimelineFacts(SettlementItemRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Optional<SettlementFact> findByOrderNo(String orderNo) {
        return repository.findByOrderNo(orderNo)
                .map(i -> new SettlementFact(i.getOrderNo(), i.getAmount(),
                        i.getStatus().name(), i.getConfirmedDate()));
    }

    /**
     * 정산 <b>항목</b>의 사실. 수수료는 여기 없다 — 수수료는 일별 집계(Settlement)에서
     * 하루치를 묶어 계산하므로 항목 단위로 존재하지 않는다. 대사에서 수수료 차이를 확인하려면
     * 그 거래일의 정산 집계를 함께 봐야 한다.
     */
    public record SettlementFact(String orderNo, long amount, String status,
                                 java.time.LocalDate confirmedDate) {
    }
}
