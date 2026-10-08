package com.beomsu.becommerce.shorts.web;

import com.beomsu.becommerce.shorts.ShortsException;
import com.beomsu.becommerce.shorts.ShortsFeedPageView;
import com.beomsu.becommerce.shorts.ShortsFeedService;
import com.beomsu.becommerce.shorts.ShortsViewSignalService;
import com.beomsu.becommerce.shorts.ViewerIdentity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 숏폼 피드 REST 컨트롤러(R26·R27·R28·R29) — 로그인 없이 누구나 부른다(SecurityConfig가 이
 * 경로들만 permitAll).
 *
 * <p>{@link ShortsController}(판매자 전용, ROLE_SELLER)와 경로 접두사(`/api/v1/shorts`)는 같지만
 * 인가 수준이 다르다 — 그래서 컨트롤러를 분리해 "이 엔드포인트만 공개"라는 사실을 코드로도 드러낸다.
 *
 * <p>피드 읽기(R26·R28·R29)와 시청 신호 쓰기(R27)를 한 컨트롤러에 둔다 — 둘 다 같은
 * {@link ViewerIdentity} 해석 규칙(JWT 있으면 로그인, 없으면 익명 식별자)을 공유하는 한 표면이라
 * 쪼개면 그 규칙을 두 번 적어야 한다.
 */
@RestController
@RequestMapping("/api/v1/shorts")
public class ShortsFeedController {

    private final ShortsFeedService feedService;
    private final ShortsViewSignalService signalService;

    public ShortsFeedController(ShortsFeedService feedService, ShortsViewSignalService signalService) {
        this.feedService = feedService;
        this.signalService = signalService;
    }

    /**
     * READY 숏폼만, R28 개인화 점수(완료율 0.5·최신성 0.3·상품 선호 일치 0.2) 내림차순으로 한
     * 쪽씩. {@code cursor}는 이전 응답의 {@code nextCursor}를 그대로 넘기면 된다(없으면 첫 쪽).
     * {@code size}가 없으면 10개. 비로그인이면 {@code anonymousId}로 본인 식별(선호 일치 계산용,
     * 없어도 조회는 된다 — 그때는 상품 선호 일치가 0으로 계산된다).
     */
    @GetMapping("/feed")
    public ShortsFeedPageView feed(@RequestParam(required = false) Long cursor,
                                   @RequestParam(required = false) Integer size,
                                   @RequestParam(required = false) String anonymousId,
                                   @AuthenticationPrincipal(errorOnInvalidType = false) Jwt jwt) {
        return feedService.feed(cursor, size, resolveViewer(jwt, anonymousId));
    }

    /**
     * 시청 신호 기록(R27) — 영상 하나를 본 세션이 끝날 때(다음 영상으로 넘어가거나 화면을 떠날
     * 때) 화면이 부른다. 로그인이면 principal의 userId, 아니면 본문의 {@code anonymousId}가
     * 있어야 한다(둘 다 없으면 400).
     */
    @PostMapping("/{id}/signals")
    public ResponseEntity<Void> recordSignal(@PathVariable long id, @RequestBody RecordViewSignalRequest request,
                                             @AuthenticationPrincipal(errorOnInvalidType = false) Jwt jwt) {
        ViewerIdentity viewer = resolveViewer(jwt, request.anonymousId());
        if (viewer == null) {
            throw ShortsException.viewerIdentityRequired();
        }
        signalService.record(id, viewer, request.watchSeconds(), request.completed(), request.replayCount(),
                request.skippedWithin3s(), request.productTagTapped());
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    private static ViewerIdentity resolveViewer(Jwt jwt, String anonymousId) {
        if (jwt != null) {
            return ViewerIdentity.ofUser(Long.parseLong(jwt.getSubject()));
        }
        if (anonymousId != null && !anonymousId.isBlank()) {
            return ViewerIdentity.ofAnonymous(anonymousId);
        }
        return null;
    }
}
