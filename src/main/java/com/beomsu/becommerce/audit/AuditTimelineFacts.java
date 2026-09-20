package com.beomsu.becommerce.audit;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * 감사로그가 타임라인에 내주는 사실 (ADR-011).
 *
 * <p><b>키가 또 다르다.</b> 감사로그는 {@code (targetType, targetId)}로 묶인다 —
 * 주문번호도 결제 id도 아닌 <b>세 번째 형태</b>다. 그리고 그게 맞다.
 * 감사로그는 "누가 무엇에 손댔나"를 남기는 것이라, 대상이 주문일 수도 결제일 수도
 * 대사 결과일 수도 있다. 한 종류로 강제하면 그 목적을 잃는다.
 *
 * <p>그래서 이 조회는 <b>대상 목록을 받는다.</b> 조립기가 앞 단계에서 알아낸 식별자들
 * (주문번호·결제 id·대사 결과 id)을 모아 넘긴다.
 */
@Service
public class AuditTimelineFacts {

    private final AuditLogRepository repository;

    AuditTimelineFacts(AuditLogRepository repository) {
        this.repository = repository;
    }

    /**
     * 주어진 대상들에 대한 감사 기록.
     *
     * @param targets {@code targetType:targetId} 쌍 목록. 비어 있으면 빈 결과 —
     *                조회할 대상이 없는 것은 오류가 아니다
     */
    @Transactional(readOnly = true)
    public List<AuditFact> findByTargets(Collection<Target> targets) {
        if (targets.isEmpty()) {
            return List.of();
        }
        return targets.stream()
                .flatMap(t -> repository
                        .findByTargetTypeAndTargetIdOrderByIdAsc(t.type(), t.id()).stream())
                .map(a -> new AuditFact(a.getCreatedAt(), a.getActor(), a.getAction(),
                        a.getTargetType(), a.getTargetId(), a.getDetail()))
                .toList();
    }

    /** 감사 대상 하나. */
    public record Target(String type, String id) {
    }

    public record AuditFact(Instant at, String actor, String action,
                            String targetType, String targetId, String detail) {
    }
}
