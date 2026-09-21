package com.beomsu.becommerce.recommendation;

import com.beomsu.becommerce.recommendation.internal.ItemPoolSource;
import com.beomsu.becommerce.recommendation.internal.RecommendationService;
import com.beomsu.becommerce.recommendation.internal.RecommendationView;
import org.springframework.stereotype.Service;

import java.util.List;

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

    private final RecommendationService service;
    private final ItemPoolSource pool;

    RecommendationFacts(RecommendationService service, ItemPoolSource pool) {
        this.service = service;
        this.pool = pool;
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
                              long modelMs, long checkMs, String generationScope) {
    }

    /** 이 사용자에게 내줄 추천. 모델이 실패해도 <b>예외를 던지지 않는다</b>(폴백이 온다). */
    public Recommended recommend(long userId) {
        RecommendationView view = service.recommend(userId);
        return new Recommended(view.items(), view.source(), view.fallbackReason(),
                view.modelMs(), view.checkMs(), view.generationScope());
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
