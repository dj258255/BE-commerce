package com.beomsu.becommerce.recommendation;

import com.beomsu.becommerce.personalization.PersonalizationUserMapFacts;
import com.beomsu.becommerce.personalization.RecentActivityFacts;
import com.beomsu.becommerce.recommendation.internal.GenPagePageClient;
import com.beomsu.becommerce.recommendation.internal.ItemPoolSource;
import com.beomsu.becommerce.recommendation.internal.ModelBusyException;
import com.beomsu.becommerce.recommendation.internal.RecommendationService;
import com.beomsu.becommerce.recommendation.internal.RecommendationView;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

/**
 * 추천 코어가 <b>다른 모듈에 내주는 계약</b> (ADR-018).
 *
 * <p><b>왜 이 클래스가 있나</b>: M7의 홈 컴포저는 추천을 <b>별도 경계</b>로 소비한다(이슈 #130 —
 * "추천 코어는 별도 경계, 홈 API가 계약으로 소비한다"). 그런데 추천 구현
 * ({@link RecommendationService}·{@link RecommendationView})은 {@code recommendation.internal}이라
 * 밖에서 import할 수 없다. 그렇다고 HTTP 컨트롤러를 다른 모듈이 부르게 하면 <b>전송 계층이 계약</b>이
 * 되어 버린다 — 홈이 추천의 URL에 묶이고, 나중에 추천을 다른 프로세스로 뗄 때 그 사실이 드러난다.
 *
 * <p>그래서 추천이 <b>무엇을 남에게 보여줄지 스스로 정해</b> 좁게 내준다 —
 * {@code RecentActivityFacts}·{@code ProductCatalogFacts}와 같은 이유, 같은 방식이다.
 * 내부 타입({@link RecommendationView})을 그대로 넘기지 않고 <b>필요한 값만 담은 record</b>로 굳힌다:
 * 홈이 알아야 하는 것은 "무엇을 추천했는가 · 어디서 왔는가 · 얼마나 걸렸는가"뿐이다.
 */
@Service
public class RecommendationFacts {

    private static final Logger log = LoggerFactory.getLogger(RecommendationFacts.class);

    private final RecommendationService service;
    private final ItemPoolSource pool;
    private final GenPagePageClient pageModel;
    private final Counter pageOk;
    private final Counter pageFailed;
    private final Counter pageBusy;
    /** 결합 방식 지표를 만들 때 쓴다. 태그가 응답마다 달라 카운터를 호출 시점에 만든다. */
    private final MeterRegistry registry;
    private final boolean pageSessionFirst;
    /** 저장소의 행동을 종류 · 시각과 함께 읽는다. {@code sessionRich} 일 때만 쓴다. */
    private final RecentActivityFacts recentActivity;
    private final boolean sessionRich;
    private final int contextLimit;
    /** 홈 2쪽의 결합 방식(S2, #455). OFF 면 요청 · 지표가 지금과 같다. */
    private final GenPageCompose composeMode;
    /** {@code userId → hm_customer_id}. 매핑이 있는 회원만 {@code compose} 를 보낸다. */
    private final PersonalizationUserMapFacts userMap;

    /** 홈 다음 쪽의 모델 입력(#270). 구매만 넣거나, 세션의 조회·클릭을 앞에 붙인다. */
    static final String PAGE_HISTORY_PURCHASES = "purchases";
    static final String PAGE_HISTORY_SESSION_THEN_PURCHASES = "session-then-purchases";

    /** 2쪽 세션을 모델에 넘기는 방식(X5, #328). */
    enum GenPageSession {
        /** 지금 그대로 — 세션은 id 만, 규칙은 {@code page-history}. 서버는 id 를 요청일의 구매로 읽는다 */
        OFF,
        /** 구매는 {@code history} 에, 조회 · 클릭은 종류 · 시각과 함께 {@code session} 에, 요청 시각은 {@code now} 에 */
        RICH
    }

    /** 홈 2쪽 GenPage 요청의 결합 방식(S2, #455). */
    enum GenPageCompose {
        /** 지금 그대로 — 매핑을 읽지도, {@code compose} 를 보내지도 않는다 */
        OFF,
        /** 서버의 규칙 줄 구성(B) */
        RULE,
        /** 순위 점수 + GenPage 하이브리드(H'(λ)) */
        HYBRID
    }

    RecommendationFacts(RecommendationService service, ItemPoolSource pool,
                        ObjectProvider<GenPagePageClient> pageModel, MeterRegistry registry) {
        this(service, pool, pageModel, registry, PAGE_HISTORY_PURCHASES);
    }

    RecommendationFacts(RecommendationService service, ItemPoolSource pool,
                        ObjectProvider<GenPagePageClient> pageModel, MeterRegistry registry, String pageHistory) {
        this(service, pool, pageModel, registry, pageHistory, null, GenPageSession.OFF, 20,
                GenPageCompose.OFF, null);
    }

    /** 테스트용(#328 의 X5): 결합 방식은 {@code OFF}, 매핑은 없다. */
    RecommendationFacts(RecommendationService service, ItemPoolSource pool,
                        ObjectProvider<GenPagePageClient> pageModel, MeterRegistry registry, String pageHistory,
                        RecentActivityFacts recentActivity, GenPageSession genPageSession, int contextLimit) {
        this(service, pool, pageModel, registry, pageHistory, recentActivity, genPageSession, contextLimit,
                GenPageCompose.OFF, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    RecommendationFacts(RecommendationService service, ItemPoolSource pool,
                        ObjectProvider<GenPagePageClient> pageModel, MeterRegistry registry,
                        @Value("${app.recommendation.page-history:purchases}") String pageHistory,
                        RecentActivityFacts recentActivity,
                        @Value("${app.recommendation.genpage-session:OFF}") GenPageSession genPageSession,
                        @Value("${app.recommendation.context-limit:20}") int contextLimit,
                        @Value("${app.recommendation.genpage-compose:OFF}") GenPageCompose genPageCompose,
                        PersonalizationUserMapFacts userMap) {
        this.pageSessionFirst = PAGE_HISTORY_SESSION_THEN_PURCHASES.equals(pageHistory);
        this.recentActivity = recentActivity;
        this.sessionRich = genPageSession == GenPageSession.RICH && recentActivity != null;
        this.contextLimit = contextLimit;
        this.composeMode = genPageCompose;
        this.userMap = userMap;
        this.registry = registry;
        this.service = service;
        this.pool = pool;
        this.pageModel = pageModel.getIfAvailable();
        this.pageOk = Counter.builder("recommendation.genpage.page").tag("result", "ok").register(registry);
        this.pageFailed = Counter.builder("recommendation.genpage.page").tag("result", "failed").register(registry);
        this.pageBusy = Counter.builder("recommendation.genpage.page").tag("result", "busy").register(registry);
    }

    /** 사용자의 실험 배정. 실험이 꺼져 있으면 둘 다 null 이다. */
    public record Experiment(String experiment, String variant) {
    }

    /**
     * 이 사용자의 실험 배정(#294). 홈 2쪽도 변형에 따라 다르므로(#270) 2쪽 노출에도 변형을 적어야 A/B 분석이 2쪽의 클릭 · 구매를
     * 귀속할 수 있다. 1쪽과 같은 규칙으로 배정하므로 같은 사용자는 같은 변형이다.
     */
    public Experiment experimentOf(long userId) {
        var assignment = service.assignmentFor(userId);
        return assignment.active() ? new Experiment(assignment.experiment(), assignment.variant()) : new Experiment(null, null);
    }

    /** 모델이 만든 행 하나 — 대분류와 그 안의 상품 id(모델의 순서). */
    public record GeneratedRow(String category, List<Long> itemIds) {
    }

    /**
     * 홈 다음 쪽의 행을 모델이 생성한다. 모델 입력은 이 모듈이 고른다(#270): 이 사용자의 추천이 구매 이력을 쓰면
     * 구매(설정에 따라 세션을 앞에 붙여)를, 아니면 세션을 넣는다. 모델이 꺼져 있으면 구매를 읽지 않는다.
     * {@code genpage-session=RICH} 면 이 규칙 대신 구매는 구매로, 세션은 종류 · 시각과 함께 따로 보낸다(X5, #328).
     *
     * <p>{@code genpage-compose} 가 {@code OFF} 가 아니면(S2, #455) 이 회원의 {@code hm_customer_id} 를 찾아
     * {@code compose} · {@code customer} 를 함께 보낸다. 매핑이 없으면 보내지 않고 그 사실을 지표로 남긴다 —
     * 표의 규칙("행이 있으면 매핑, 없으면 폴백")을 그대로 따른다.
     *
     * @param session 이 요청 시점의 최근 조회·클릭(최근 것부터)
     */
    public List<GeneratedRow> generatePageRows(long userId, List<Long> session, Collection<Long> exclude,
                                               Collection<String> excludeCategories, int rows, int itemsPerRow) {
        if (pageModel == null) {
            return List.of();
        }
        // OFF 면 매핑을 읽지 않는다 — 요청도 지표도 지금과 같아야 한다.
        String compose = composeValue();
        String customer = compose == null ? null : customerOf(userId);
        if (compose != null && customer == null) {
            // 매핑이 없으면 compose 를 보내지 않는다(지금 동작). 이유를 지표에 남긴다.
            composeMetric("none", "no_mapping");
        }
        boolean composed = compose != null && customer != null;
        if (sessionRich) {
            // X5(#328): id 만 넘기면 서버가 클릭을 요청일의 구매로 읽는다. 저장소가 가진 종류 · 시각을 그대로 넘긴다
            List<Long> bought = service.purchaseHistoryForModel(userId);
            List<GenPagePageClient.SessionEvent> events = new ArrayList<>();
            for (RecentActivityFacts.RecentActivity a : recentActivity.recentActivities(userId, contextLimit).reversed()) {
                events.add(new GenPagePageClient.SessionEvent(a.itemId(), a.type(), a.occurredAt()));
            }
            List<Long> history = bought == null ? List.of() : bought;
            if (composed) {
                return generatedComposed(() -> pageModel.generateComposed(history, events, Instant.now(),
                        exclude, excludeCategories, rows, itemsPerRow, compose, customer));
            }
            return generated(() -> pageModel.generate(history, events, Instant.now(),
                    exclude, excludeCategories, rows, itemsPerRow));
        }
        List<Long> history = pageHistory(userId, session);
        if (composed) {
            return generatedComposed(() -> pageModel.generateComposed(history, null, null,
                    exclude, excludeCategories, rows, itemsPerRow, compose, customer));
        }
        return generated(() -> pageModel.generate(history, exclude, excludeCategories, rows, itemsPerRow));
    }

    /** 설정된 결합 방식의 요청 값. {@code OFF} 면 null 이다 — 이 값이 있으면 매핑을 읽는다. */
    String composeValue() {
        return switch (composeMode) {
            case OFF -> null;
            case RULE -> "rule";
            case HYBRID -> "hybrid";
        };
    }

    /** 매핑 표에서 이 회원의 H&amp;M 고객 키를 찾는다. 매핑이 없거나 읽을 수 없으면 null 이다(폴백 경로). */
    private String customerOf(long userId) {
        return userMap == null ? null : userMap.hmCustomerId(userId).orElse(null);
    }

    /**
     * 모델에 넣을 이력. GenPage 는 구매 시퀀스로 학습했으니 구매가 학습과 같은 입력이다. 세션을 앞에 붙이면
     * 1쪽 이후 본 것이 가장 최근 토큰이 되어 행 선택에 반영될 수 있다 — 대가는 학습에 없던 조회가 섞이는 것이다.
     */
    List<Long> pageHistory(long userId, List<Long> session) {
        List<Long> bought = service.purchaseHistoryForModel(userId);
        if (bought == null) {
            return session;
        }
        if (!pageSessionFirst || session.isEmpty()) {
            return bought;
        }
        List<Long> out = new ArrayList<>(session.size() + bought.size());
        out.addAll(session);
        out.addAll(bought);
        return out;
    }

    /**
     * 홈 다음 쪽의 행을 <b>모델이 생성한다</b>(GenPage, #238). 모델이 꺼져 있거나 실패하면 <b>빈 목록</b>이다 —
     * 예외를 던지지 않는다. 홈은 빈 목록을 받으면 규칙 행(대분류 인기)으로 물러선다. 실패는 지표로 센다.
     *
     * @param history 최근 활동(최근 것부터)
     * @param exclude 앞 쪽에서 보여 준 상품 — 모델이 생성 중에 막는다
     * @param excludeCategories 앞 쪽에서 이미 행이 된 대분류
     */
    public List<GeneratedRow> generatePageRows(List<Long> history, Collection<Long> exclude,
                                               Collection<String> excludeCategories, int rows, int itemsPerRow) {
        if (pageModel == null) {
            return List.of();
        }
        return generated(() -> pageModel.generate(history, exclude, excludeCategories, rows, itemsPerRow));
    }

    /** 모델 호출 하나를 결과 · 실패 · 자리 없음으로 센다. 실패하면 빈 목록이다(홈은 규칙 행으로 물러선다). */
    private List<GeneratedRow> generated(Supplier<List<GenPagePageClient.Row>> call) {
        try {
            List<GeneratedRow> out = toRows(call.get());
            pageOk.increment();
            return out;
        } catch (ModelBusyException e) {
            // 모델 자리가 없었다(#271) — 실패가 아니라 부하 정책이 고른 결과다. 로그를 남기지 않고 센다
            pageBusy.increment();
            return List.of();
        } catch (RuntimeException e) {
            pageFailed.increment();
            log.warn("GenPage 행 생성 실패 → 규칙 행으로 물러선다: {}", e.toString());
            return List.of();
        }
    }

    /**
     * {@code compose} 요청의 호출 하나를 세고, 서버가 밝힌 {@code composition} · {@code fallback} 을 지표 태그로 남긴다(S2).
     * 응답이 없으면(자리 없음 · 실패) 둘 다 {@code none} 이다 — <b>시도했지만 결합 결과가 없다</b>는 뜻이다.
     */
    private List<GeneratedRow> generatedComposed(Supplier<GenPagePageClient.Page> call) {
        try {
            GenPagePageClient.Page page = call.get();
            composeMetric(orNone(page.composition()), orNone(page.fallback()));
            List<GeneratedRow> out = toRows(page.rows());
            pageOk.increment();
            return out;
        } catch (ModelBusyException e) {
            composeMetric("none", "none");
            pageBusy.increment();
            return List.of();
        } catch (RuntimeException e) {
            composeMetric("none", "none");
            pageFailed.increment();
            log.warn("GenPage 행 생성 실패 → 규칙 행으로 물러선다: {}", e.toString());
            return List.of();
        }
    }

    private static List<GeneratedRow> toRows(List<GenPagePageClient.Row> rows) {
        return rows.stream().map(r -> new GeneratedRow(r.category(), r.itemIds())).toList();
    }

    /**
     * 결합 결과를 센다. 태그가 응답 값이라 카운터를 호출 시점에 만든다 — 값의 가짓수는 composition 셋 ·
     * fallback 넷으로 좁다(no_mapping · none · no_scores · exclude …).
     */
    private void composeMetric(String composition, String fallback) {
        registry.counter("recommendation.genpage.compose",
                "composition", composition, "fallback", fallback).increment();
    }

    private static String orNone(String value) {
        return value == null || value.isBlank() ? "none" : value;
    }

    /**
     * 홈이 소비하는 추천 결과.
     *
     * <p>{@code source}가 {@code FALLBACK}이면 모델을 못 부른 것이다 — 홈은 이 사실을 <b>감추지 않고</b>
     * 화면에 밝힌다(E3의 "폴백은 설계된 응답"이라는 규칙이 홈까지 이어진다).
     *
     * <p>{@code score}는 없다. <b>모델 스텁은 순위만 내지 점수를 내지 않는다</b> — 없는 점수를 지어내면
     * 화면이 "관련도 0.91"처럼 보이지만 그 숫자의 근거가 없다. 홈은 순위를 그대로 쓴다.
     */
    public record Recommended(List<Long> itemIds, String source, String fallbackReason,
                              long modelMs, long checkMs, String generationScope,
                              String experiment, String variant) {

        /** 실험이 없을 때(#256 이전 호출부). */
        public Recommended(List<Long> itemIds, String source, String fallbackReason,
                           long modelMs, long checkMs, String generationScope) {
            this(itemIds, source, fallbackReason, modelMs, checkMs, generationScope, null, null);
        }
    }

    /** 이 사용자에게 내줄 추천. 모델이 실패해도 <b>예외를 던지지 않는다</b>(폴백이 온다). */
    public Recommended recommend(long userId) {
        RecommendationView view = service.recommend(userId);
        return new Recommended(view.items(), view.source(), view.fallbackReason(),
                view.modelMs(), view.checkMs(), view.generationScope(), view.experiment(), view.variant());
    }

    /**
     * 후보 집합이 아는 "인기" 목록 — 홈이 인기 행을 만들 때 쓴다.
     *
     * <p><b>{@code limit} 이 있는 이유(#198①)</b>: 홈이 되채우기를 켜면 인기 행이 <b>더 깊은 순위</b>를
     * 요구한다(중복·다양성 상한으로 빈 칸을 채우려면 후보가 더 필요하다). 인기 표는 200행을 들고 있고
     * 조회는 인덱스 한 페이지라 <b>깊이 늘리는 값이 싸다</b> — 그래서 이 원천만 깊이를 받는다.
     */
    public List<Long> popularItemIds(int limit) {
        return pool.popular(limit);
    }
}
