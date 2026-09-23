package com.beomsu.becommerce.recommendation.web;

import com.beomsu.becommerce.recommendation.internal.RecommendationMetrics;
import com.beomsu.becommerce.recommendation.internal.RecommendationService;
import com.beomsu.becommerce.recommendation.internal.RecommendationView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.time.Duration;

/**
 * 추천 표면 — 홈이 부르는 한 엔드포인트.
 *
 * <p>userId는 <b>인증 principal</b>에서 얻는다(개인화·주문·위시리스트와 같은 규칙). 경로나 질의로
 * 받으면 남의 활동으로 만든 추천을 읽을 수 있다.
 *
 * <p><b>모델이 못 대답해도 200이다.</b> 폴백은 정상 응답이고, 어느 쪽인지는 몸통의 {@code source}가
 * 밝힌다. 5xx로 답하면 홈 전체가 실패로 보이는데, 실제로는 인기 상품이 나갔다 —
 * 화면이 할 수 있는 일(폴백 표시)을 없애는 셈이다.
 */
@RestController
@RequestMapping("/api/v1/recommendations")
public class RecommendationController {

    private final RecommendationService service;
    private final RecommendationMetrics metrics;

    public RecommendationController(RecommendationService service, RecommendationMetrics metrics) {
        this.service = service;
        this.metrics = metrics;
    }

    @GetMapping
    public RecommendationView recommend(Principal principal) {
        long startedAt = System.nanoTime();
        try {
            return service.recommend(Long.parseLong(principal.getName()));
        } finally {
            metrics.servingTimer().record(Duration.ofNanos(System.nanoTime() - startedAt));
        }
    }
}
