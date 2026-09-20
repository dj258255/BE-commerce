package com.beomsu.becommerce.assist.review;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * 블라인드 리뷰 어드민 (ADR-014).
 *
 * <p><b>초안은 공개 단계 전까지 응답에 실리지 않는다.</b> 화면이 "미리 참고만" 할 수 없게
 * 서버가 막는다. 클라이언트를 믿고 감추면 언젠가 누군가 개발자 도구를 연다.
 *
 * <p>리뷰어는 인증 주체에서 가져온다. 파라미터로 받으면 남의 이름으로 표본을 넣을 수 있고,
 * 그러면 "같은 사람이 두 번 리뷰하지 않는다"는 제약이 무의미해진다.
 */
@RestController
@RequestMapping("/api/v1/admin/assist/reviews")
class BlindReviewAdminController {

    private final BlindReviewService service;

    BlindReviewAdminController(BlindReviewService service) {
        this.service = service;
    }

    /** 1단계 시작 — 사실만 받는다. */
    @PostMapping("/start")
    BlindReviewView start(@RequestParam long reconResultId, @RequestParam String orderNo,
                          Authentication auth) {
        return service.start(reconResultId, orderNo, actor(auth));
    }

    /** 1단계 제출 — 사실만 보고 쓴 답. */
    @PostMapping("/{id}/blind")
    BlindReviewView blind(@PathVariable long id, @RequestBody TextBody body) {
        return service.submitBlind(id, body.text());
    }

    /** 2단계 — 초안 둘 공개(A/B). 1단계 전이면 서비스가 거부한다. */
    @PostMapping("/{id}/reveal")
    BlindReviewView reveal(@PathVariable long id) {
        return service.reveal(id);
    }

    /**
     * 3단계 — 초안 A 의 수정본. <b>A 가 모델인지 템플릿인지는 서버만 안다.</b>
     * 경로에 모델/템플릿을 쓰면 개발자 도구를 연 순간 눈가림이 풀린다.
     */
    @PostMapping("/{id}/edited/a")
    BlindReviewView editedA(@PathVariable long id, @RequestBody TextBody body) {
        return service.submitFirst(id, body.text());
    }

    /** 3단계 — 초안 B 의 수정본. */
    @PostMapping("/{id}/edited/b")
    BlindReviewView editedB(@PathVariable long id, @RequestBody TextBody body) {
        return service.submitSecond(id, body.text());
    }

    /** 집계 — 표본 수와 한계를 함께 낸다. 쌍 비교는 둘 다 고친 건에서만 나온다. */
    @GetMapping("/stats")
    BlindReviewStats stats() {
        return service.stats();
    }

    private static String actor(Authentication auth) {
        return auth == null ? "unknown" : auth.getName();
    }

    record TextBody(String text) {
    }
}
