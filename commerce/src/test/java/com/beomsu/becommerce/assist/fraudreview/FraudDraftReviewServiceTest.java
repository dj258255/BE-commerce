package com.beomsu.becommerce.assist.fraudreview;

import com.beomsu.becommerce.fraud.FraudReviewFacts;
import com.beomsu.becommerce.fraud.FraudReviewFactsPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 블라인드 비교를 고정한다. <b>순서가 이 실험의 유일한 방법론적 근거다.</b>
 *
 * <p>초안을 보고 나서 자기 메모를 쓰면 그 문장에 끌린다. 그러면 "고칠 게 없었다" 와 "고칠
 * 생각이 안 났다" 가 구분되지 않고, 두 편집률의 차이가 방식 차이가 아니게 된다.
 */
@DisplayName("심사 초안 블라인드 비교 — 보기 전에 쓰고, 어느 쪽이 모델인지 안 알린다")
class FraudDraftReviewServiceTest {

    private static final long REVIEW_ID = 7L;
    private static final String ME = "beomsu";

    private final List<FraudDraftReview> rows = new ArrayList<>();
    private FraudDraftReviewService service;

    private static FraudReviewFacts facts() {
        return new FraudReviewFacts(REVIEW_ID, "ORD-1", 11L, "tgen****9f2a", 320_000L, 60, "REVIEW",
                Instant.parse("2026-09-08T02:00:00Z"),
                List.of(new FraudReviewFacts.FiredRule("HIGH_AMOUNT", null, 40, 0.85)),
                4, 3);
    }

    /** 저장소를 리스트로 대신한다. 이 서비스의 요점은 순서 강제이고 그건 엔티티가 한다. */
    private FraudDraftReviewRepository repository() {
        return new FraudDraftReviewRepository() {
            @Override public Optional<FraudDraftReview> findByFraudReviewIdAndReviewer(long id, String r) {
                return rows.stream()
                        .filter(x -> x.getFraudReviewId() == id && x.getReviewer().equals(r))
                        .findFirst();
            }
            @Override public List<FraudDraftReview> findAll() { return rows; }
            @Override public <S extends FraudDraftReview> S save(S entity) {
                if (!rows.contains(entity)) rows.add(entity);
                return entity;
            }
            // 나머지는 이 시험에서 안 쓴다.
            @Override public void flush() {}
            @Override public <S extends FraudDraftReview> S saveAndFlush(S e) { return save(e); }
            @Override public <S extends FraudDraftReview> List<S> saveAllAndFlush(Iterable<S> e) { throw new UnsupportedOperationException(); }
            @Override public void deleteAllInBatch(Iterable<FraudDraftReview> e) {}
            @Override public void deleteAllByIdInBatch(Iterable<Long> ids) {}
            @Override public void deleteAllInBatch() {}
            @Override public FraudDraftReview getOne(Long id) { throw new UnsupportedOperationException(); }
            @Override public FraudDraftReview getById(Long id) { throw new UnsupportedOperationException(); }
            @Override public FraudDraftReview getReferenceById(Long id) { throw new UnsupportedOperationException(); }
            @Override public <S extends FraudDraftReview> List<S> findAll(org.springframework.data.domain.Example<S> ex) { throw new UnsupportedOperationException(); }
            @Override public <S extends FraudDraftReview> List<S> findAll(org.springframework.data.domain.Example<S> ex, org.springframework.data.domain.Sort s) { throw new UnsupportedOperationException(); }
            @Override public <S extends FraudDraftReview> List<S> saveAll(Iterable<S> e) { throw new UnsupportedOperationException(); }
            @Override public List<FraudDraftReview> findAllById(Iterable<Long> ids) { throw new UnsupportedOperationException(); }
            @Override public Optional<FraudDraftReview> findById(Long id) { throw new UnsupportedOperationException(); }
            @Override public boolean existsById(Long id) { throw new UnsupportedOperationException(); }
            @Override public long count() { return rows.size(); }
            @Override public void deleteById(Long id) {}
            @Override public void delete(FraudDraftReview e) {}
            @Override public void deleteAllById(Iterable<? extends Long> ids) {}
            @Override public void deleteAll(Iterable<? extends FraudDraftReview> e) {}
            @Override public void deleteAll() { rows.clear(); }
            @Override public List<FraudDraftReview> findAll(org.springframework.data.domain.Sort s) { return rows; }
            @Override public org.springframework.data.domain.Page<FraudDraftReview> findAll(org.springframework.data.domain.Pageable p) { throw new UnsupportedOperationException(); }
            @Override public <S extends FraudDraftReview> Optional<S> findOne(org.springframework.data.domain.Example<S> ex) { throw new UnsupportedOperationException(); }
            @Override public <S extends FraudDraftReview> org.springframework.data.domain.Page<S> findAll(org.springframework.data.domain.Example<S> ex, org.springframework.data.domain.Pageable p) { throw new UnsupportedOperationException(); }
            @Override public <S extends FraudDraftReview> long count(org.springframework.data.domain.Example<S> ex) { return 0; }
            @Override public <S extends FraudDraftReview> boolean exists(org.springframework.data.domain.Example<S> ex) { return false; }
            @Override public <S extends FraudDraftReview, R> R findBy(org.springframework.data.domain.Example<S> ex, java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> fn) { throw new UnsupportedOperationException(); }
        };
    }

    private static FraudReviewDraftPort fixed(String text, String name) {
        return new FraudReviewDraftPort() {
            @Override public Optional<String> draft(FraudReviewFacts f) { return Optional.ofNullable(text); }
            @Override public String name() { return name; }
        };
    }

    private FraudReviewFactsPort port() {
        return id -> id == REVIEW_ID ? Optional.of(facts()) : Optional.empty();
    }

    @BeforeEach
    void setUp() {
        rows.clear();
        service = new FraudDraftReviewService(port(),
                List.of(fixed("모델이 쓴 문장", "ollama:qwen3:8b"), new TemplateFraudReviewAdapter()),
                new TemplateFraudReviewAdapter(), repository(), drafts(port()));
    }

    @Test
    @DisplayName("자기 메모를 쓰기 전에는 초안을 못 본다")
    void cannotRevealBeforeBlind() {
        service.open(REVIEW_ID, ME);

        assertThatThrownBy(() -> service.reveal(REVIEW_ID, ME))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("순서");
    }

    @Test
    @DisplayName("초안을 본 뒤에는 자기 메모를 못 쓴다 — 그 문장에 끌린다")
    void cannotWriteBlindAfterReveal() {
        service.open(REVIEW_ID, ME);
        service.blind(REVIEW_ID, ME, "카드 이력을 봐야 할 것 같다");
        service.reveal(REVIEW_ID, ME);

        assertThatThrownBy(() -> service.blind(REVIEW_ID, ME, "다시 쓴다"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("다시 열어도 같은 초안·같은 순서다 — 채점이 그 화면과 맞아야 한다")
    void revealIsStable() {
        service.open(REVIEW_ID, ME);
        service.blind(REVIEW_ID, ME, "메모");
        var first = service.reveal(REVIEW_ID, ME).orElseThrow();
        String model = first.getModelDraft();
        boolean order = first.isBaselineFirst();

        var again = service.reveal(REVIEW_ID, ME).orElseThrow();

        assertThat(again.getModelDraft()).isEqualTo(model);
        assertThat(again.isBaselineFirst()).isEqualTo(order);
        assertThat(again.getRevealedAt()).isEqualTo(first.getRevealedAt());
    }

    @Test
    @DisplayName("모델 초안이 비면 공개하지 않는다 — 한쪽만 있는 화면은 비교가 아니다")
    void oneSidedRevealIsRefused() {
        service = new FraudDraftReviewService(port(), List.of(fixed(null, "ollama")),
                new TemplateFraudReviewAdapter(), repository(), drafts(port()));
        service.open(REVIEW_ID, ME);
        service.blind(REVIEW_ID, ME, "메모");

        assertThat(service.reveal(REVIEW_ID, ME)).isEmpty();
    }

    @Test
    @DisplayName("한쪽만 고친 건은 성적에 안 들어간다")
    void halfEditedIsNotCounted() {
        service.open(REVIEW_ID, ME);
        service.blind(REVIEW_ID, ME, "메모");
        service.reveal(REVIEW_ID, ME);
        service.edit(REVIEW_ID, ME, "모델 초안을 고쳤다", null);

        assertThat(service.stats().judged()).isZero();
    }

    @Test
    @DisplayName("표본이 얇으면 모델이 낮아도 켤 조건을 안 넘긴다")
    void thinSampleDoesNotActivate() {
        service.open(REVIEW_ID, ME);
        service.blind(REVIEW_ID, ME, "메모");
        service.reveal(REVIEW_ID, ME);
        // 모델 초안은 거의 안 고치고 템플릿은 통째로 고친 건 하나
        service.edit(REVIEW_ID, ME, "모델이 쓴 문장!", "완전히 다시 쓴 아주 긴 문장으로 바꿔 버렸다");

        var stats = service.stats();
        assertThat(stats.judged()).isEqualTo(1);
        assertThat(stats.modelMedian()).isLessThan(stats.baselineMedian());
        assertThat(stats.modelBeatsBaseline())
                .as("1건에서 모델이 낮게 나온 것은 우연과 구별되지 않는다")
                .isFalse();
    }

    @Test
    @DisplayName("표본이 없으면 중앙값이 없다 — 0 으로 채우면 켠 것처럼 읽힌다")
    void emptySampleHasNoMedian() {
        var stats = service.stats();
        assertThat(stats.judged()).isZero();
        assertThat(stats.modelMedian()).isNull();
        assertThat(stats.baselineMedian()).isNull();
        assertThat(stats.modelBeatsBaseline()).isFalse();
    }

    @Test
    @DisplayName("없는 심사는 못 연다")
    void missingReviewCannotOpen() {
        assertThat(service.open(999L, ME)).isEmpty();
    }

    @Test
    @DisplayName("모델이 안 떠 있으면 공개를 거절한다 — 템플릿 둘을 비교하면 차이가 0으로 나온다")
    void withoutModelThereIsNoComparison() {
        var onlyTemplate = new FraudDraftReviewService(port(),
                List.of(new TemplateFraudReviewAdapter()),
                new TemplateFraudReviewAdapter(), repository(), drafts(port()));

        assertThat(onlyTemplate.modelAvailable())
                .as("표본 0건과 모델이 꺼진 것을 화면이 구별해야 한다")
                .isFalse();

        onlyTemplate.open(REVIEW_ID, ME);
        onlyTemplate.blind(REVIEW_ID, ME, "메모");
        assertThat(onlyTemplate.reveal(REVIEW_ID, ME)).isEmpty();
        assertThat(onlyTemplate.stats().judged()).isZero();
    }

    @Test
    @DisplayName("공개된 초안 둘은 서로 다른 구현이 만든 것이다")
    void revealedPairComesFromTwoImplementations() {
        service.open(REVIEW_ID, ME);
        service.blind(REVIEW_ID, ME, "메모");
        var row = service.reveal(REVIEW_ID, ME).orElseThrow();

        assertThat(row.getModelSource()).isNotEqualTo(row.getBaselineSource());
        assertThat(row.getModelDraft()).isNotEqualTo(row.getBaselineDraft());
    }

    /**
     * 비교가 <b>화면에 나갈 때와 같은 처리</b>를 태우므로 그 서비스를 함께 준다.
     * provider 이름은 템플릿으로 둔다 — 비교 쪽은 포트를 명시적으로 넘기므로 여기 값은 안 쓰인다.
     */
    private static FraudReviewDraftService drafts(FraudReviewFactsPort port) {
        return new FraudReviewDraftService(port, java.util.List.of(new TemplateFraudReviewAdapter()),
                new TemplateFraudReviewAdapter(),
                new com.beomsu.becommerce.assist.draft.NumericProvenanceGuard(),
                new com.beomsu.becommerce.assist.draft.AmountCoverageGuard(), new FactWideningGuard(),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), "template");
    }
}
