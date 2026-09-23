package com.beomsu.becommerce.dispute.internal;

import com.beomsu.becommerce.dispute.DisputeOutcome;
import com.beomsu.becommerce.dispute.DisputeOutcomePort;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 분쟁 결과를 밖으로 내보낸다. <b>읽기만 한다.</b>
 *
 * <p>한 주문에 분쟁이 여러 번 걸릴 수 있다. 라벨로는 <b>승패가 갈린 마지막 것</b>을 쓴다.
 * 진행 중인 건을 섞으면 심사가 밀릴수록 라벨이 좋아 보이는 것과 같은 문제가 생긴다.
 * 상황 6.1 에서 오탐률의 분모를 처리된 건으로 묶은 것과 같은 이유다.
 */
@Service
class DisputeOutcomeService implements DisputeOutcomePort {

    private final DisputeRepository repository;

    DisputeOutcomeService(DisputeRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DisputeOutcome> outcomeOf(String orderNo) {
        var all = repository.findByOrderNoOrderByIdAsc(orderNo);
        // 승패가 갈린 마지막 것. 없으면 마지막 것을 그대로 준다(진행 중이라는 사실도 사실이다).
        return all.stream()
                .filter(d -> d.getStatus() == DisputeStatus.WON || d.getStatus() == DisputeStatus.LOST)
                .reduce((a, b) -> b)
                .or(() -> all.isEmpty() ? Optional.empty() : Optional.of(all.getLast()))
                .map(DisputeOutcomeService::toOutcome);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DisputeOutcome> settledSince(Instant since, int limit) {
        return repository.findByStatusInAndResolvedAtGreaterThanEqualOrderByResolvedAtDesc(
                        List.of(DisputeStatus.WON, DisputeStatus.LOST), since,
                        PageRequest.of(0, limit))
                .stream().map(DisputeOutcomeService::toOutcome).toList();
    }

    private static DisputeOutcome toOutcome(Dispute d) {
        return new DisputeOutcome(d.getOrderNo(), d.getChargebackId(), d.getReason(),
                d.getStatus().name(), d.getResolvedAt());
    }
}
