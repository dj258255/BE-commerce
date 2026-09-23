package com.beomsu.becommerce.assist.fraudreview;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 심사 초안 하나에 대한 블라인드 비교 한 건.
 *
 * <p><b>순서가 이 실험의 유일한 방법론적 근거다.</b> 심사자가 초안을 보기 <b>전에</b> 자기
 * 메모를 먼저 쓴다. 보고 나서 쓰면 그 문장에 끌려가 "고칠 게 없었다" 와 "고칠 생각이 안
 * 났다" 가 구분되지 않는다. 상태 전이가 그 순서를 강제한다.
 *
 * <pre>
 *   열림 → blind(자기 메모) → reveal(초안 둘 공개) → edit(각각 고침)
 * </pre>
 *
 * <p><b>어느 쪽이 모델인지 안 알린다.</b> 알고 고치면 두 편집률의 차이가 방식 차이가 아니게
 * 된다. 표시 순서도 공개 시점에 뽑아 고정한다. 항상 같은 쪽을 먼저 보여주면 먼저 본 것에
 * 기준이 생긴다.
 */
@Entity
@Table(name = "fraud_draft_reviews")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FraudDraftReview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private long fraudReviewId;

    @Column(nullable = false)
    private String reviewer;

    /**
     * 누가 평가했는가. <b>HUMAN 만 전환 조건을 채운다.</b>
     *
     * <p>전환 조건은 <b>사람 심사자가</b> 초안을 얼마나 고쳐야 하는지를 묻는다. 모델이 고친 양은
     * 같은 질문의 답이 아니다 — 화면을 보는 것은 사람이다. 그래서 한 표에 두되 갈라서 센다.
     * 섞으면 나중에 "그 12 건이 누구 것이었나"에 기록이 답을 못 한다.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private EvaluatorKind evaluatorKind = EvaluatorKind.HUMAN;

    // <b>{@code @Lob} 를 안 쓴다.</b> Hibernate 가 MySQL 에서 longtext 를 기대해
    // TEXT 로 만든 마이그레이션과 어긋나 ddl-auto=validate 가 기동을 막는다.
    // 같은 이유로 V26 의 blind_reviews 도 columnDefinition 을 명시한다.
    @Column(columnDefinition = "TEXT")
    private String blindReply;
    private Instant blindAt;

    @Column(columnDefinition = "TEXT")
    private String modelDraft;
    private String modelSource;
    @Column(columnDefinition = "TEXT")
    private String baselineDraft;
    private String baselineSource;

    @Column(nullable = false)
    private boolean baselineFirst;
    private Instant revealedAt;

    @Column(columnDefinition = "TEXT")
    private String editedDraft;
    private Instant editedAt;
    @Column(columnDefinition = "TEXT")
    private String editedBaseline;
    private Instant baselineEditedAt;

    @Column(nullable = false)
    private Instant createdAt;

    /**
     * 평가 주체. 늘리려면 여기에 더한다 — 문자열로 두면 오타가 조용히 새 종류가 된다.
     *
     * <p><b>{@code HUMAN} 만 전환 조건을 채운다.</b> 조건이 묻는 것은 사람 심사자가
     * <b>혼자</b> 초안을 얼마나 고쳐야 하는지다.
     *
     * <p>{@code HUMAN_AI_ASSISTED} 는 사람이 판정했지만 그 과정에서 외부 AI 의 도움을 받은
     * 것이다. 편집량이 보조의 영향을 받고, <b>경과 시간에는 AI 에 문의한 시간이 들어간다.</b>
     * 그래서 사람 것과 같은 칸에 놓을 수 없다.
     */
    public enum EvaluatorKind { HUMAN, AI, HUMAN_AI_ASSISTED }

    private FraudDraftReview(long fraudReviewId, String reviewer, EvaluatorKind evaluatorKind) {
        this.evaluatorKind = evaluatorKind;
        this.fraudReviewId = fraudReviewId;
        this.reviewer = reviewer;
        this.createdAt = Instant.now();
    }

    public static FraudDraftReview open(long fraudReviewId, String reviewer) {
        return open(fraudReviewId, reviewer, EvaluatorKind.HUMAN);
    }

    /** 평가 주체를 밝혀 연다. 모델이 평가하는 경우는 반드시 이쪽으로 온다. */
    public static FraudDraftReview open(long fraudReviewId, String reviewer, EvaluatorKind kind) {
        return new FraudDraftReview(fraudReviewId, reviewer, kind);
    }

    /** 1단계. 초안을 보기 전에 쓴다. */
    public void blind(String reply) {
        if (revealedAt != null) {
            throw new IllegalStateException("초안을 이미 봤다. 이제 쓰는 메모는 그 문장에 끌린다");
        }
        this.blindReply = reply;
        this.blindAt = Instant.now();
    }

    /**
     * 2단계. 초안 둘을 <b>고정</b>한다.
     *
     * <p>고정하는 이유는 모델이 부를 때마다 다르게 쓰기 때문이다. 나중에 채점할 때 화면에
     * 뜬 것과 다른 문장을 재면 그 수치는 아무것도 아니다.
     */
    public void reveal(String modelDraft, String modelSource,
                       String baselineDraft, String baselineSource, boolean baselineFirst) {
        if (blindAt == null) {
            throw new IllegalStateException("자기 메모를 먼저 써야 한다. 순서가 이 실험의 근거다");
        }
        if (revealedAt != null) {
            return;   // 다시 열어도 같은 문장·같은 순서라야 채점이 그 화면과 맞는다
        }
        this.modelDraft = modelDraft;
        this.modelSource = modelSource;
        this.baselineDraft = baselineDraft;
        this.baselineSource = baselineSource;
        this.baselineFirst = baselineFirst;
        this.revealedAt = Instant.now();
    }

    /** 3단계. 어느 쪽을 고친 것인지는 화면이 아니라 여기서 가른다. */
    public void edit(String editedModel, String editedBaseline) {
        if (revealedAt == null) {
            throw new IllegalStateException("공개 전에는 고칠 초안이 없다");
        }
        if (editedModel != null) {
            this.editedDraft = editedModel;
            this.editedAt = Instant.now();
        }
        if (editedBaseline != null) {
            this.editedBaseline = editedBaseline;
            this.baselineEditedAt = Instant.now();
        }
    }

    /** 두 쪽 다 고쳐야 한 건으로 센다. 한쪽만 고친 건을 넣으면 비교가 안 된다. */
    public boolean complete() {
        return editedDraft != null && editedBaseline != null
                && modelDraft != null && baselineDraft != null;
    }
}
