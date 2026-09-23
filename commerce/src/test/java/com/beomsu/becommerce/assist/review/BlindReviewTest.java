package com.beomsu.becommerce.assist.review;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 블라인드 리뷰의 <b>순서</b>를 지킨다. 이게 이 실험의 유일한 방법론적 근거다.
 *
 * <p>문서나 관례로 두면 사람이 급할 때 건너뛴다. 그리고 건너뛴 표본은
 * <b>조용히 무의미해진다</b> — 겉보기엔 데이터가 쌓이는데 앵커링에 오염돼 있다.
 * 그래서 엔티티가 막는다.
 */
class BlindReviewTest {

    private BlindReview review() {
        return BlindReview.start(1L, "ORD-1", "beomsu");
    }

    /** 고정하고 공개까지. 운영 경로가 둘을 이어 부른다. */
    private BlindReview revealed(BlindReview r, String model, String baseline) {
        r.preload(model, "ollama", baseline, "template", false);
        r.markRevealed();
        return r;
    }

    @Test
    @DisplayName("블라인드 답변 전에는 초안을 공개할 수 없다")
    void cannotRevealBeforeBlindReply() {
        assertThatThrownBy(() -> revealed(review(), "모델 초안", "템플릿 초안"))
                .isInstanceOf(BlindReviewException.class)
                .hasMessageContaining("먼저 제출");
    }

    @Test
    @DisplayName("블라인드 답변은 한 번만 — 고쳐 쓰면 이미 본 셈이 된다")
    void blindReplyIsWriteOnce() {
        BlindReview r = review();
        r.submitBlind("첫 답변");
        assertThatThrownBy(() -> r.submitBlind("고친 답변"))
                .isInstanceOf(BlindReviewException.class)
                .hasMessageContaining("다시 쓸 수 없");
    }

    @Test
    @DisplayName("빈 답변은 표본이 되지 않는다")
    void blankBlindReplyRejected() {
        assertThatThrownBy(() -> review().submitBlind("   "))
                .isInstanceOf(BlindReviewException.class);
    }

    @Test
    @DisplayName("공개는 멱등 — 다시 불러도 처음 본 초안이 유지된다")
    void revealIsIdempotent() {
        BlindReview r = review();
        r.submitBlind("내 답변");
        revealed(r, "첫 초안", "첫 템플릿");
        revealed(r, "두 번째 초안", "두 번째 템플릿");     // 모델은 매번 다르게 쓴다

        assertThat(r.getModelDraft())
                .as("사람이 실제로 본 것을 채점해야 한다")
                .isEqualTo("첫 초안");
        assertThat(r.getBaselineDraft())
                .as("대조군도 같이 고정된다. 한쪽만 다시 뽑으면 짝이 어긋난다")
                .isEqualTo("첫 템플릿");
        assertThat(r.isBaselineFirst())
                .as("표시 순서도 고정된다. 다시 열었을 때 순서가 바뀌면 채점이 화면과 안 맞는다")
                .isFalse();
    }

    @Test
    @DisplayName("초안을 미리 심어 둬도 블라인드 답 전에는 공개가 아니다")
    void preloadDoesNotReveal() {
        BlindReview r = review();
        r.preload("모델 초안", "ollama", "템플릿 초안", "template", false);

        assertThat(r.preloaded()).isTrue();
        assertThat(r.revealed())
                .as("초안이 있느냐로 가르면 심는 순간 실험의 전제가 조용히 깨진다")
                .isFalse();
        assertThatThrownBy(r::markRevealed)
                .isInstanceOf(BlindReviewException.class)
                .hasMessageContaining("먼저 제출");
    }

    @Test
    @DisplayName("순서 검사는 초안을 만들기 전에 한다 — 거절될 요청에 모델을 부르지 않는다")
    void orderIsCheckedBeforeDraftsAreMade() {
        BlindReview r = review();
        // 초안 없이도 순서 위반이 잡혀야 한다. 서비스가 이걸 먼저 불러 모델 호출을 아낀다.
        assertThatThrownBy(r::requireBlindDone)
                .isInstanceOf(BlindReviewException.class)
                .extracting(e -> ((BlindReviewException) e).code())
                .isEqualTo("REVIEW_OUT_OF_ORDER");

        r.submitBlind("내 답변");
        r.requireBlindDone();     // 이제는 안 던진다
    }

    @Test
    @DisplayName("공개 전에는 수정본을 받을 수 없다")
    void cannotEditBeforeReveal() {
        BlindReview r = review();
        r.submitBlind("내 답변");
        assertThatThrownBy(() -> r.submitEdited("수정본"))
                .isInstanceOf(BlindReviewException.class);
    }

    @Test
    @DisplayName("정상 순서는 통과한다")
    void happyPath() {
        BlindReview r = review();
        r.submitBlind("사실만 보고 쓴 답");
        revealed(r, "모델 초안", "템플릿 초안");
        r.submitEdited("초안을 고친 것");

        assertThat(r.blindDone()).isTrue();
        assertThat(r.revealed()).isTrue();
        assertThat(r.editDone()).isTrue();
        assertThat(r.pairDone())
                .as("한쪽만 고치면 짝이 없어 두 중앙값을 같은 표본에서 못 낸다")
                .isFalse();

        r.submitEditedBaseline("대조군을 고친 것");
        assertThat(r.pairDone()).isTrue();
    }

    @Test
    @DisplayName("대조군 수정본도 공개 전에는 못 받는다")
    void cannotEditBaselineBeforeReveal() {
        BlindReview r = review();
        r.submitBlind("내 답변");
        assertThatThrownBy(() -> r.submitEditedBaseline("수정본"))
                .isInstanceOf(BlindReviewException.class)
                .extracting(e -> ((BlindReviewException) e).code())
                .isEqualTo("REVIEW_OUT_OF_ORDER");
    }

    @Test
    @DisplayName("순서 위반은 오류가 아니라 <설계된 거절>이다 — 코드로 구분된다")
    void outOfOrderHasItsOwnCode() {
        assertThatThrownBy(() -> revealed(review(), "초안", "템플릿"))
                .isInstanceOf(BlindReviewException.class)
                .extracting(e -> ((BlindReviewException) e).code())
                .as("500이 아니라 409로 나가야 호출자가 버그와 구분한다")
                .isEqualTo("REVIEW_OUT_OF_ORDER");
    }

    @Test
    @DisplayName("빈 답변은 순서 위반이 아니라 입력 오류 — 400과 409를 구분한다")
    void blankInputIsNotAnOrderViolation() {
        assertThatThrownBy(() -> review().submitBlind(" "))
                .isInstanceOf(BlindReviewException.class)
                .extracting(e -> ((BlindReviewException) e).code())
                .isEqualTo("INVALID_REVIEW_INPUT");
    }
}
