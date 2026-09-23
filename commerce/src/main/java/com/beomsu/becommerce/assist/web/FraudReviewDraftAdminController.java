package com.beomsu.becommerce.assist.web;

import com.beomsu.becommerce.assist.fraudreview.FraudReviewDraft;
import com.beomsu.becommerce.assist.fraudreview.FraudReviewDraftService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 심사 한 건의 초안을 내려 준다. <b>읽기만 한다.</b>
 *
 * <p><b>승인·거부와 같은 컨트롤러에 두지 않았다.</b> `/api/v1/admin/fraud-reviews` 는 fraud
 * 모듈의 창구이고 상태를 바꾼다. 초안은 assist 의 읽기 기능이라 여기 둔다. 한 컨트롤러에
 * 섞으면 초안 생성 경로에서 심사를 닫는 길이 열리고, 그건 상황 5.1 에서 없앤 뒷문과 같은
 * 모양이 된다.
 *
 * <p><b>없는 심사는 404, 초안을 못 만든 심사는 204 다.</b> 둘을 같은 응답으로 묶으면 화면이
 * "그런 건이 없다"와 "초안이 안 나왔다"를 구별하지 못한다. 상황 5.1 에서 조회 실패와 기록
 * 없음을 가른 것과 같은 이유다.
 */
@RestController
@RequestMapping("/api/v1/admin/fraud-reviews")
class FraudReviewDraftAdminController {

    private final FraudReviewDraftService drafts;

    FraudReviewDraftAdminController(FraudReviewDraftService drafts) {
        this.drafts = drafts;
    }

    @GetMapping("/{id}/draft")
    ResponseEntity<FraudReviewDraft> draft(@PathVariable long id) {
        return drafts.draftFor(id)
                .map(d -> d.present() ? ResponseEntity.ok(d)
                                      : ResponseEntity.noContent().<FraudReviewDraft>build())
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
