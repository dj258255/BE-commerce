package com.beomsu.becommerce.reconciliation.internal;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

// 하위 패키지가 같은 모듈 안에서 참조하므로 public 이다. 모듈 밖 접근은 package-private 이 아니라
// ModularityTests 의 allowedDependencies 가 막는다.
public interface ReconciliationResultRepository extends JpaRepository<ReconciliationResult, Long> {

    /** 어드민 관측용 — 상태별 대사 결과 페이지(운영이 PENDING 예외 큐를 조회). 전건 로딩 방지 위해 페이지 단위. */
    Page<ReconciliationResult> findByStatus(ReconStatus status, Pageable pageable);

    /** 타임라인 조립용(ADR-011). 같은 주문이 여러 날 대사에 걸릴 수 있어 목록이다 — 시간대 경계 판단의 재료. */
    java.util.List<ReconciliationResult> findByOrderNoOrderByIdAsc(String orderNo);

    /** SLO 게이지용 — 상태별 대사 결과 건수(운영이 PENDING 적체를 관측). */
    long countByStatus(ReconStatus status);

    /** PENDING 건의 내부·외부 금액 차이 절대값 합. 내부 전용·외부 전용도 미설명 금액으로 포함한다. */
    @org.springframework.data.jpa.repository.Query("""
            select coalesce(sum(abs(coalesce(r.internalAmount, 0) - coalesce(r.externalAmount, 0))), 0)
              from ReconciliationResult r
             where r.status = :status
            """)
    long sumUnexplainedAmountByStatus(ReconStatus status);

    /** 가장 오래된 미해결 건의 나이를 계산하는 운영 지표용 조회. */
    Optional<ReconciliationResult> findTopByStatusOrderByReconciledAtAsc(ReconStatus status);

    /** 재실행 멱등: 그 거래일 판정을 지우고 다시 쓴다. 같은 파일을 두 번 올려도 큐가 늘지 않는다. */
    void deleteByTradeDate(LocalDate tradeDate);

    /**
     * 확정된 건을 (유형, 사람이 고른 원인)으로 묶어 센다. 규칙 승격 후보를 뽑는 재료다.
     *
     * <p><b>DB에서 묶는다.</b> 확정 이력은 계속 쌓이는데 애플리케이션으로 다 끌어와 세면
     * 상황 2에서 고친 것과 같은 종류의 배치가 하나 더 생긴다.
     *
     * <p>{@code resolveCause} 가 없는 건은 뺀다. 자동 확정이나 옛 데이터라 사람의 판단이 아니다.
     */
    @org.springframework.data.jpa.repository.Query("""
            select r.result as type, r.resolveCause as cause, count(r) as cnt
              from ReconciliationResult r
             where r.status = :status
               and r.resolveCause is not null
             group by r.result, r.resolveCause
            """)
    java.util.List<ResolvedCauseCount> countResolvedByTypeAndCause(ReconStatus status);

    /** 위 집계의 한 줄. 프로젝션이라 인터페이스로 받는다. */
    interface ResolvedCauseCount {
        ReconResultType getType();
        com.beomsu.becommerce.reconciliation.ResolveCause getCause();
        long getCnt();
    }
}
