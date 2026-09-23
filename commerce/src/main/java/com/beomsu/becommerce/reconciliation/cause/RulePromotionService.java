package com.beomsu.becommerce.reconciliation.cause;

import com.beomsu.becommerce.reconciliation.internal.ReconciliationResultRepository;
import com.beomsu.becommerce.reconciliation.internal.ReconStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 사람이 확정한 이력에서 <b>규칙으로 올릴 만한 자리</b>를 찾는다.
 *
 * <p>대사가 안 맞으면 사람이 원인을 골라 확정한다. 그 이력이 쌓이면 어떤 유형은 늘 같은 원인으로
 * 끝난다. 그건 이미 규칙인데 사람 손을 계속 거치고 있는 것이다.
 *
 * <p><b>여기서 자동으로 규칙을 만들지 않는다.</b> 후보를 세어 보여 주는 데까지만 한다.
 * 규칙을 켜는 것은 돈 판정을 바꾸는 일이라 사람이 근거를 보고 눌러야 한다. 이 서비스가
 * 하는 일은 <b>"이 자리는 볼 필요가 있다"를 숫자로 만드는 것</b>까지다.
 */
@Service
public class RulePromotionService {

    private final ReconciliationResultRepository repository;

    public RulePromotionService(ReconciliationResultRepository repository) {
        this.repository = repository;
    }

    /**
     * 사람이 수기 확정한 이력을 (유형, 원인)으로 묶어 후보를 낸다.
     *
     * <p>승격 기준을 넘긴 것만 주지 않고 <b>전부 준다.</b> 넘긴 것만 주면 화면이
     * "후보 없음"일 때 <b>표본이 아직 얇은 것인지 사람 판단이 갈리는 것인지 구분이 안 된다.</b>
     * 그 둘은 다음에 할 일이 다르다.
     */
    @Transactional(readOnly = true)
    public List<RulePromotionCandidate> candidates() {
        var rows = repository.countResolvedByTypeAndCause(ReconStatus.MANUALLY_RESOLVED);

        Map<String, Long> totalByType = new HashMap<>();
        for (var row : rows) {
            totalByType.merge(row.getType().name(), row.getCnt(), Long::sum);
        }

        List<RulePromotionCandidate> out = new ArrayList<>();
        for (var row : rows) {
            String type = row.getType().name();
            long total = totalByType.get(type);
            double share = total == 0 ? 0 : (double) row.getCnt() / total;
            out.add(new RulePromotionCandidate(type, row.getCause(), row.getCnt(), total, share));
        }

        // 올릴 만한 것부터. 같은 조건이면 건수가 많은 쪽이 먼저다.
        out.sort(Comparator
                .comparing(RulePromotionCandidate::promotable).reversed()
                .thenComparing(Comparator.comparingLong(RulePromotionCandidate::resolved).reversed()));
        return out;
    }
}
