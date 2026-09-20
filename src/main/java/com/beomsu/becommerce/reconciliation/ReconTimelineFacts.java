package com.beomsu.becommerce.reconciliation;

import com.beomsu.becommerce.reconciliation.internal.ReconciliationResultRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 대사가 타임라인에 내주는 사실 (ADR-011).
 *
 * <p>이 타임라인의 주 사용처가 대사 원인 판단이므로, 자기 자신의 기록도 함께 보여야 한다 —
 * 같은 주문이 <b>여러 날 대사에 걸렸는지</b>가 원인 판단의 재료다(예: 시간대 경계).
 */
@Service
public class ReconTimelineFacts {

    private final ReconciliationResultRepository repository;

    ReconTimelineFacts(ReconciliationResultRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<ReconFact> findByOrderNo(String orderNo) {
        return repository.findByOrderNoOrderByIdAsc(orderNo).stream()
                .map(r -> new ReconFact(r.getId(), r.getReconciledAt(), r.getTradeDate(), r.getResult().name(),
                        r.getInternalAmount(), r.getExternalAmount(), r.getStatus().name(),
                        r.getResolvedAt(), r.getResolvedBy(),
                        r.getResolveCause() == null ? null : r.getResolveCause().name()))
                .toList();
    }

    /**
     * @param id 대사 결과 식별자. <b>감사로그가 이 id로 묶이므로</b> 조립기가 알아야 한다
     *           (감사로그 키는 targetType=RECONCILIATION_RESULT, targetId=이 값).
     */
    public record ReconFact(Long id, Instant reconciledAt, java.time.LocalDate tradeDate, String result,
                            Long internalAmount, Long externalAmount, String status,
                            Instant resolvedAt, String resolvedBy, String resolveCause) {
    }
}
