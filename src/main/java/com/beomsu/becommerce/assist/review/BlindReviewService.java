package com.beomsu.becommerce.assist.review;

import com.beomsu.becommerce.reconciliation.cause.ClassifierAccuracyMetrics;
import com.beomsu.becommerce.assist.draft.CsDraft;
import com.beomsu.becommerce.assist.draft.DraftService;
import com.beomsu.becommerce.assist.draft.FactPack;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 블라인드 리뷰 — 초안이 <b>쓸 만한지</b>를 재는 유일한 경로 (ADR-014).
 *
 * <p><b>왜 필요한가</b>: 지금까지 잰 것은 "지어낸 숫자가 없다"(검증 통과율 100%)와
 * "내부 용어가 안 샌다"(0%)뿐이다. <b>둘 다 "보낼 만하다"는 뜻이 아니다.</b>
 * 그걸 재려면 사람이 초안을 얼마나 고치는지를 봐야 한다.
 *
 * <p><b>순서가 방법론이다.</b>
 * <ol>
 *   <li>사실만 보여주고 사람이 <b>직접</b> 답을 쓴다 — 모델 초안을 보기 전에</li>
 *   <li>그 뒤 모델 초안을 공개한다</li>
 *   <li>사람이 초안을 발송 가능하게 고친다</li>
 * </ol>
 * 1번을 건너뛰면 사람이 초안에 끌려가(앵커링) "고칠 게 없었다"와 "고칠 생각이 안 났다"가
 * 구분되지 않는다. 이건 {@code ClassifierAccuracyMetrics} 가 자기 수치를 정확도가 아니라
 * "일치율"이라고 부르는 것과 같은 문제이고, 여기서는 <b>엔티티가 순서를 강제</b>한다.
 *
 * <p><b>두 가지를 잰다.</b>
 * <ul>
 *   <li><b>편집률</b> (모델 → 수정본): 상담원에게 얼마나 일이 남는가</li>
 *   <li><b>괴리</b> (블라인드 답 ↔ 모델 초안): 사람과 모델이 얼마나 다르게 쓰는가</li>
 * </ul>
 * 앞은 실무 지표, 뒤는 앵커링에 오염되지 않은 유일한 값이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BlindReviewService {

    /** 이 아래면 "그대로 썼다"로 본다. 조사·띄어쓰기 정도만 만진 수준. */
    private static final double AS_IS = 0.05;
    /** 이 위면 "새로 썼다"로 본다. 절반 넘게 바뀌면 초안이 도움이 됐다고 보기 어렵다. */
    private static final double REWRITE = 0.5;

    private final BlindReviewRepository repository;
    private final DraftService draftService;

    /**
     * 1단계 시작 — <b>사실만</b> 돌려준다. 초안은 만들지도 않는다.
     *
     * <p>여기서 초안을 미리 만들어 두면 응답에 실을 유혹이 생긴다. 만들지 않는 것이
     * 가장 확실한 차단이다.
     */
    @Transactional
    public BlindReviewView start(long reconResultId, String orderNo, String reviewer) {
        BlindReview review = repository.findByReconResultIdAndReviewer(reconResultId, reviewer)
                .orElseGet(() -> repository.save(
                        BlindReview.start(reconResultId, orderNo, reviewer)));
        return toView(review);
    }

    /** 1단계 제출 — 사람이 사실만 보고 쓴 답. */
    @Transactional
    public BlindReviewView submitBlind(long reviewId, String reply) {
        BlindReview review = find(reviewId);
        review.submitBlind(reply);
        return toView(repository.save(review));
    }

    /**
     * 2단계 — 모델 초안 공개. <b>1단계 전에는 엔티티가 막는다.</b>
     *
     * <p>공개 시점에 초안을 <b>고정</b>한다. 모델은 같은 입력에도 매번 다르게 쓰므로,
     * 나중에 다시 뽑으면 사람이 실제로 본 것과 다른 문장을 채점하게 된다.
     */
    @Transactional
    public BlindReviewView reveal(long reviewId) {
        BlindReview review = find(reviewId);
        if (!review.revealed()) {
            // 순서를 <먼저> 막는다. 초안을 만들고 나서 막으면 거절될 요청에 모델을 두 번 부른다.
            review.requireBlindDone();
            // 미리 심어 둔 표본이면 그대로 쓴다. 여기서 다시 뽑으면 모델이 다르게 써서
            // 심을 때 정한 A/B 배정과 어긋난다.
            if (!review.preloaded()) {
                fixDrafts(review);
            }
            review.markRevealed();
            repository.save(review);
        }
        return toView(review);
    }

    /**
     * 초안 둘을 고정한다. <b>공개는 아니다.</b>
     *
     * <p>미리 심는 경로(`BlindReviewSeedTest`)와 그 자리에서 만드는 경로가 같은 코드를 쓴다.
     * 갈라 두면 한쪽만 고쳐져서 심은 표본과 그때 만든 표본이 다른 규칙으로 생긴다.
     */
    @Transactional
    public void fixDrafts(BlindReview review) {
        CsDraft model = draftService.draftFor(review.getOrderNo(), review.getReconResultId());
        CsDraft base = draftService.baselineFor(review.getOrderNo(), review.getReconResultId());
        // 초안이 없는 것도 결과다. 빈 문자열로 고정해 "초안이 없었다"를 표본에 남긴다.
        // 여기서 예외를 던지면 그 케이스가 통계에서 조용히 빠진다.
        if (model.text() == null) {
            log.info("[blind] 초안 없음 review={} source={} rejected={}",
                    review.getId(), model.source(), model.rejected());
        }
        review.preload(text(model), model.source(), text(base), base.source(),
                ThreadLocalRandom.current().nextBoolean());
    }

    /** 표본 심기 — 초안만 고정해 둔다. 사람은 나중에 답만 쓴다. */
    @Transactional
    public int preloadAll(long reconResultId, String orderNo, String reviewer) {
        BlindReview review = repository.findByReconResultIdAndReviewer(reconResultId, reviewer)
                .orElseGet(() -> repository.save(BlindReview.start(reconResultId, orderNo, reviewer)));
        if (review.preloaded()) {
            return 0;
        }
        fixDrafts(review);
        repository.save(review);
        return 1;
    }

    private static String text(CsDraft d) {
        return d.text() == null ? "" : d.text();
    }

    /** 3단계 — 먼저 보여준 초안의 수정본. */
    @Transactional
    public BlindReviewView submitFirst(long reviewId, String edited) {
        BlindReview review = find(reviewId);
        // 화면은 A/B 로만 부른다. 어느 쪽이 모델인지는 서버만 안다.
        if (review.isBaselineFirst()) {
            review.submitEditedBaseline(edited);
        } else {
            review.submitEdited(edited);
        }
        return toView(repository.save(review));
    }

    /** 3단계 — 나중에 보여준 초안의 수정본. */
    @Transactional
    public BlindReviewView submitSecond(long reviewId, String edited) {
        BlindReview review = find(reviewId);
        if (review.isBaselineFirst()) {
            review.submitEdited(edited);
        } else {
            review.submitEditedBaseline(edited);
        }
        return toView(repository.save(review));
    }

    /**
     * 집계. <b>표본 수를 항상 같이 낸다.</b>
     *
     * <p>평균이 아니라 중앙값을 쓴다. 표본이 적을 때 한 건이 통째로 다시 쓰인 것만으로
     * 평균이 끌려가고, 그러면 "대체로 어떤가"를 못 본다.
     */
    @Transactional(readOnly = true)
    public BlindReviewStats stats() {
        List<BlindReview> done = repository.findByEditedAtIsNotNull();
        if (done.isEmpty()) {
            return BlindReviewStats.empty();
        }
        List<Double> edits = new ArrayList<>();
        List<Double> diverge = new ArrayList<>();
        int asIs = 0, rewritten = 0;

        for (BlindReview r : done) {
            double e = TextDistance.editRatio(r.getModelDraft(), r.getEditedDraft());
            edits.add(e);
            diverge.add(TextDistance.editRatio(r.getBlindReply(), r.getModelDraft()));
            if (e < AS_IS) asIs++;
            if (e >= REWRITE) rewritten++;
        }

        // 쌍 비교는 <둘 다 고친> 건에서만 낸다. 한쪽만 있는 건을 섞으면 두 중앙값이
        // 서로 다른 표본에서 나와, 차이가 방식 차이인지 표본 차이인지 갈리지 않는다.
        List<Double> pairModel = new ArrayList<>();
        List<Double> pairBase = new ArrayList<>();
        for (BlindReview r : done) {
            if (!r.pairDone()) continue;
            pairModel.add(TextDistance.editRatio(r.getModelDraft(), r.getEditedDraft()));
            pairBase.add(TextDistance.editRatio(r.getBaselineDraft(), r.getEditedBaseline()));
        }

        List<String> caveat = new ArrayList<>();
        caveat.add("편집률은 <표현이 얼마나 다른가>를 재지 <내용이 맞는가>를 재지 않는다.");
        caveat.add("리뷰어는 상담원이 아니라 개발자다. 실제 상담 기준과 다를 수 있다.");
        if (done.size() < 20) {
            caveat.add("표본 " + done.size() + "건은 통계로 쓰기에 적다. 경향만 본다.");
        }
        if (pairModel.isEmpty()) {
            caveat.add("쌍 비교 표본이 없다. 활성화 조건 1번(편집률이 템플릿보다 낮을 것)은 "
                    + "초안 둘을 다 고친 건에서만 나온다.");
        } else if (pairModel.size() < done.size()) {
            caveat.add("쌍 비교는 " + pairModel.size() + "건에서만 냈다"
                    + "(나머지는 한쪽만 고쳐 짝이 없다).");
        }
        return new BlindReviewStats(done.size(), median(edits), median(diverge),
                asIs, rewritten,
                pairModel.size(),
                pairModel.isEmpty() ? null : median(pairModel),
                pairBase.isEmpty() ? null : median(pairBase),
                List.copyOf(caveat));
    }

    private static double median(List<Double> xs) {
        List<Double> sorted = xs.stream().sorted(Comparator.naturalOrder()).toList();
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2)
                : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
    }

    private BlindReview find(long id) {
        return repository.findById(id).orElseThrow(() -> BlindReviewException.notFound(id));
    }

    /**
     * 단계에 따라 보여줄 것을 고른다. 초안은 공개 전까지 <b>응답에 실리지 않는다.</b>
     *
     * <p>공개 뒤에도 <b>어느 쪽이 모델인지는 안 내보낸다.</b> A/B 로만 준다.
     */
    private BlindReviewView toView(BlindReview r) {
        String stage = r.pairDone() ? "DONE" : r.revealed() ? "REVEALED" : "BLIND";

        // 사실은 1단계에서 필요하고, 그 뒤에도 대조에 쓴다. 초안과 달리 감출 이유가 없다.
        FactPack facts = draftService.factsFor(r.getOrderNo(), r.getReconResultId());

        boolean baseFirst = r.isBaselineFirst();
        String a = null, b = null, ea = null, eb = null;
        if (r.revealed()) {                                  // ← 공개 전에는 전부 null
            a  = baseFirst ? r.getBaselineDraft()  : r.getModelDraft();
            b  = baseFirst ? r.getModelDraft()     : r.getBaselineDraft();
            ea = baseFirst ? r.getEditedBaseline() : r.getEditedDraft();
            eb = baseFirst ? r.getEditedDraft()    : r.getEditedBaseline();
        }
        return new BlindReviewView(r.getId(), r.getReconResultId(), r.getOrderNo(), stage,
                facts.facts(), facts.causeHint(), r.getBlindReply(), a, b, ea, eb);
    }
}
