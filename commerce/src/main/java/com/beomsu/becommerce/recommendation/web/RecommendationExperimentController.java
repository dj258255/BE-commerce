package com.beomsu.becommerce.recommendation.web;

import com.beomsu.becommerce.recommendation.internal.RecommendationService;
import com.beomsu.becommerce.recommendation.internal.RecommendationView;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 서빙 경로 채점용: 지정한 사용자의 추천을 받는다(#254).
 *
 * <p>정식 엔드포인트({@code /api/v1/recommendations})는 인증 principal 의 추천만 준다. 홀드아웃 고객 수천 명을 채점하려고 그만큼
 * 가입·로그인시키는 대신 이 엔드포인트를 쓴다. <b>남의 추천을 읽는 문</b>이라 {@code app.recommendation.experiment.enabled=true}
 * 일 때만 빈이 생긴다(기본 off, 기본 프로파일에서는 404). 인증은 필요하다.
 */
@RestController
@RequestMapping("/api/v1/experiments/recommendations")
@ConditionalOnProperty(name = "app.recommendation.experiment.enabled", havingValue = "true")
public class RecommendationExperimentController {

    private final RecommendationService service;

    public RecommendationExperimentController(RecommendationService service) {
        this.service = service;
    }

    @GetMapping
    public RecommendationView recommendFor(@RequestParam long userId) {
        return service.recommend(userId);
    }
}
