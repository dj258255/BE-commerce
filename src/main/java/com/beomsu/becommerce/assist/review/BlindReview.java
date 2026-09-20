package com.beomsu.becommerce.assist.review;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 블라인드 리뷰 한 건 — 초안이 <b>쓸 만한지</b>를 재는 표본 (ADR-014).
 *
 * <p><b>순서가 이 실험의 전부다.</b> 사람이 모델 초안을 보기 <b>전에</b> 자기 답을 먼저 쓴다.
 * 보고 나서 쓰면 그 문장에 끌려가(앵커링) "고칠 게 없었다"와 "고칠 생각이 안 났다"가
 * 구분되지 않는다. 그래서 상태 전이를 엔티티가 강제한다 —
 * {@code blindReply} 없이는 {@link #reveal} 을 부를 수 없다.
 *
 * <p>모델 초안을 <b>공개 시점에 고정</b>하는 이유: 모델은 같은 입력에도 매번 다르게 쓴다.
 * 나중에 다시 뽑으면 사람이 실제로 본 것과 다른 문장을 채점하게 된다.
 */
@Entity
@Table(name = "blind_reviews")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BlindReview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long reconResultId;
    private String orderNo;
    private String reviewer;

    @Column(columnDefinition = "TEXT")
    private String blindReply;
    private Instant blindAt;

    @Column(columnDefinition = "TEXT")
    private String modelDraft;
    private String modelSource;
    private Instant revealedAt;

    /** 대조군. 지금 쓰고 있는 템플릿 초안을 <b>같은 사실에서 같은 시점에</b> 고정한다. */
    @Column(columnDefinition = "TEXT")
    private String baselineDraft;
    private String baselineSource;

    /**
     * 표시 순서. 항상 같은 쪽을 먼저 보여주면 <b>먼저 본 것</b>에 기준이 생겨,
     * 뒤에 온 초안은 그 문장과 얼마나 다른지로 읽힌다. 공개 시점에 뽑아 고정한다.
     */
    private boolean baselineFirst;

    @Column(columnDefinition = "TEXT")
    private String editedDraft;
    private Instant editedAt;

    @Column(columnDefinition = "TEXT")
    private String editedBaseline;
    private Instant baselineEditedAt;

    private Instant createdAt;

    private BlindReview(Long reconResultId, String orderNo, String reviewer) {
        this.reconResultId = reconResultId;
        this.orderNo = orderNo;
        this.reviewer = reviewer;
        this.createdAt = Instant.now();
    }

    static BlindReview start(Long reconResultId, String orderNo, String reviewer) {
        return new BlindReview(reconResultId, orderNo, reviewer);
    }

    /** 1단계 — 사실만 보고 쓴 답. 한 번만 받는다(고쳐 쓰면 이미 본 셈이 된다). */
    void submitBlind(String reply) {
        if (blindReply != null) {
            throw BlindReviewException.outOfOrder(
                    "이미 블라인드 답변을 제출했습니다. 다시 쓸 수 없습니다.");
        }
        if (reply == null || reply.isBlank()) {
            throw BlindReviewException.invalid("빈 답변은 표본이 되지 않습니다.");
        }
        this.blindReply = reply.trim();
        this.blindAt = Instant.now();
    }

    /**
     * 초안 <b>둘</b>을 고정한다. <b>공개하지는 않는다.</b>
     *
     * <p>고정과 공개를 가르는 이유: 초안을 만드는 데 건당 10초 안팎이 든다. 그 자리에서
     * 만들면 리뷰어가 답을 제출할 때마다 그만큼 기다리고, <b>그 대기가 표본이 0건이던
     * 실제 이유</b>였다(쌍 비교에서 이미 겪었다). 미리 심어 두고 사람은 쓰기만 한다.
     *
     * <p>모델 초안과 템플릿 초안을 같이 고정한다. 활성화 조건이
     * "편집률 중앙값이 <b>템플릿보다</b> 낮을 것"이라, 둘을 같은 사례에서 재야 한다.
     * 회차를 나눠 재면 두 번째 회차는 리뷰어가 이미 답을 아는 상태다.
     */
    void preload(String draft, String source, String baseline, String baselineSource,
                 boolean baselineFirst) {
        if (revealedAt != null) {
            return;              // 이미 사람이 본 것은 안 바꾼다
        }
        this.modelDraft = draft;
        this.modelSource = source;
        this.baselineDraft = baseline;
        this.baselineSource = baselineSource;
        this.baselineFirst = baselineFirst;
    }

    /**
     * 2단계 — 고정해 둔 초안을 공개한다. <b>1단계 전에는 못 부른다.</b>
     *
     * <p><b>공개 여부는 초안이 있느냐가 아니라 이 시각으로 가른다.</b> 초안 존재로 가르면
     * 표본을 미리 심는 순간 <b>블라인드 답을 쓰기 전에 공개 상태</b>가 되어 실험의 전제가
     * 조용히 깨진다. 화면은 그대로 도는데 표본만 무의미해지는 종류의 사고다.
     */
    void markRevealed() {
        requireBlindDone();
        if (revealedAt != null) {
            return;              // 멱등 — 이미 본 것을 그대로 돌려준다
        }
        if (modelDraft == null) {
            throw BlindReviewException.invalid("공개할 초안이 없습니다. 먼저 고정해야 합니다.");
        }
        this.revealedAt = Instant.now();
    }

    /**
     * 순서를 먼저 막는다. <b>초안을 만들기 전에</b> 불러야 한다.
     *
     * <p>만들고 나서 막으면 거절될 요청에 모델을 두 번 부른다. 초안이 둘이라 20초 넘게
     * 쓰고 나서 409 가 나가고, 그동안 화면은 멈춘 것처럼 보인다. 배관을 실제로 태워 보고
     * 찾았다.
     */
    void requireBlindDone() {
        if (blindReply == null) {
            throw BlindReviewException.outOfOrder(
                    "블라인드 답변을 먼저 제출해야 합니다. 초안을 먼저 보면 표본이 오염됩니다.");
        }
    }

    /** 초안이 고정돼 있나. 공개와 다른 상태다. */
    public boolean preloaded() { return modelDraft != null; }

    /** 3단계 — 모델 초안을 발송 가능하게 고친 결과. */
    void submitEdited(String edited) {
        requireRevealed();
        this.editedDraft = edited == null ? "" : edited.trim();
        this.editedAt = Instant.now();
    }

    /** 3단계 — 템플릿 초안 쪽. 리뷰어는 어느 쪽이 어느 것인지 모른 채 고친다. */
    void submitEditedBaseline(String edited) {
        requireRevealed();
        this.editedBaseline = edited == null ? "" : edited.trim();
        this.baselineEditedAt = Instant.now();
    }

    private void requireRevealed() {
        if (revealedAt == null) {
            throw BlindReviewException.outOfOrder("초안을 공개하기 전에는 수정본을 받을 수 없습니다.");
        }
    }

    public boolean blindDone()  { return blindReply != null; }
    public boolean revealed()   { return revealedAt != null; }
    public boolean editDone()   { return editedAt != null; }
    public boolean baselineEditDone() { return baselineEditedAt != null; }
    /** 둘 다 고쳐야 쌍 비교 표본이 된다. 한쪽만 있으면 짝이 없어 중앙값을 못 맞댄다. */
    public boolean pairDone()   { return editDone() && baselineEditDone(); }
}
