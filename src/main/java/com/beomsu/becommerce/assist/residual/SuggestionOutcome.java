package com.beomsu.becommerce.assist.residual;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 제안이 사람의 확정과 맞았는지 <b>한 행으로</b> 남긴다.
 *
 * <p><b>왜 지표가 아니라 행인가</b>: 자동 확정의 조건이 "그 유형의 실측 오류율이 선언한 한도
 * 안"이다. 그런데 승인 여부를 프로메테우스 카운터로만 세고 있어서 <b>재시작하면 사라지고,
 * 원인 유형별로 갈리지도 않았다.</b> 유형별 오류율을 못 내면 자동 확정은 영영 못 켠다.
 *
 * <p>즉 <b>사람이 승인하는 단계가 자동화 단계의 데이터를 만든다.</b> 그 데이터를 안 남기면
 * 사다리의 다음 칸이 생기지 않는다.
 *
 * <p><b>blind 를 함께 남긴다.</b> 제안을 보여준 뒤 고르게 하면 앵커링이 생겨 일치율이 올라간다.
 * 섞어서 세면 그 수치를 믿을 수 없으므로 갈라 둔다.
 */
@Entity
@Getter
@Table(name = "suggestion_outcomes")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SuggestionOutcome {

    /** 무엇이 일어났나. */
    public enum Outcome {
        /** 제안과 사람의 확정이 같았다. */
        ACCEPTED,
        /** 제안이 있었는데 사람이 다른 것을 골랐다. <b>이것이 오류율의 분자다.</b> */
        REJECTED,
        /** 제안 자리는 왔는데 기권했다. 틀린 것과 구별한다. */
        ABSTAINED,
        /** 제안 자체가 없었다. 화면이 안 불렀거나 기록이 만료됐다. */
        NO_SUGGESTION
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "recon_result_id", nullable = false)
    private Long reconResultId;

    /** 제안한 원인. 기권했으면 {@code null}. */
    @Column(name = "suggested_cause", length = 40)
    private String suggestedCause;

    @Column(name = "chosen_cause", nullable = false, length = 40)
    private String chosenCause;

    @Column(nullable = false, length = 16)
    private String outcome;

    @Column(nullable = false)
    private boolean blind;

    @Column(name = "resolved_by", length = 100)
    private String resolvedBy;

    @Column(name = "suggested_at")
    private Instant suggestedAt;

    @Column(name = "resolved_at", nullable = false)
    private Instant resolvedAt;

    private SuggestionOutcome(Long reconResultId, String suggestedCause, String chosenCause,
                              Outcome outcome, boolean blind, String resolvedBy,
                              Instant suggestedAt, Instant resolvedAt) {
        this.reconResultId = reconResultId;
        this.suggestedCause = suggestedCause;
        this.chosenCause = chosenCause;
        this.outcome = outcome.name().toLowerCase();
        this.blind = blind;
        this.resolvedBy = resolvedBy;
        this.suggestedAt = suggestedAt;
        this.resolvedAt = resolvedAt;
    }

    public static SuggestionOutcome of(Long reconResultId, String suggestedCause, String chosenCause,
                                       Outcome outcome, boolean blind, String resolvedBy,
                                       Instant suggestedAt, Instant resolvedAt) {
        return new SuggestionOutcome(reconResultId, suggestedCause, chosenCause, outcome, blind,
                resolvedBy, suggestedAt, resolvedAt == null ? Instant.now() : resolvedAt);
    }
}
