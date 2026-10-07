package com.beomsu.becommerce.shorts.web;

import com.beomsu.becommerce.shorts.ShortsFeedPageView;
import com.beomsu.becommerce.shorts.ShortsFeedService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 숏폼 피드 REST 컨트롤러(R26) — 로그인 없이 누구나 부른다(SecurityConfig가 이 경로만 permitAll).
 *
 * <p>{@link ShortsController}(판매자 전용, ROLE_SELLER)와 경로 접두사(`/api/v1/shorts`)는 같지만
 * 인가 수준이 다르다 — 그래서 컨트롤러를 분리해 "이 엔드포인트만 공개"라는 사실을 코드로도 드러낸다.
 */
@RestController
@RequestMapping("/api/v1/shorts")
public class ShortsFeedController {

    private final ShortsFeedService feedService;

    public ShortsFeedController(ShortsFeedService feedService) {
        this.feedService = feedService;
    }

    /**
     * READY 숏폼만, id 내림차순(최신순)으로 한 쪽씩. {@code cursor}는 이전 응답의
     * {@code nextCursor}를 그대로 넘기면 된다(없으면 첫 쪽). {@code size}가 없으면 10개.
     */
    @GetMapping("/feed")
    public ShortsFeedPageView feed(@RequestParam(required = false) Long cursor,
                                   @RequestParam(required = false) Integer size) {
        return feedService.feed(cursor, size);
    }
}
