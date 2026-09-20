package com.beomsu.becommerce.assist.residual;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface SuggestionOutcomeRepository extends JpaRepository<SuggestionOutcome, Long> {

    /**
     * 유형별 일치·불일치 집계. <b>자동 확정 승격 판단이 이 결과를 본다.</b>
     *
     * <p>{@code blind} 로 갈라서 낸다. 제안을 보여준 뒤 고르게 하면 앵커링이 생겨 일치율이
     * 올라가므로, 섞어서 세면 그 수치로 승격을 정할 수 없다.
     */
    @Query("""
            select o.suggestedCause, o.blind, o.outcome, count(o)
            from SuggestionOutcome o
            where o.suggestedCause is not null
            group by o.suggestedCause, o.blind, o.outcome
            """)
    List<Object[]> tallyByCause();

    boolean existsByReconResultId(Long reconResultId);
}
