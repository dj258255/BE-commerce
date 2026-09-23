package com.beomsu.becommerce.assist.web;

import com.beomsu.becommerce.assist.evidence.DisputeEvidence;
import com.beomsu.becommerce.assist.evidence.DisputeEvidenceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 분쟁 증빙 자료를 <b>모아만 준다</b>. 제출하지 않는다.
 *
 * <p><b>왜 dispute 모듈이 아니라 여기인가</b>: 증빙 조립은 열한 개 도메인을 가로질러 읽는
 * 일이고, 그건 {@code assist} 모듈이 하는 일이다. 처음에 {@code dispute} 안에 뒀더니
 * <b>{@code dispute → assist → timeline → dispute} 순환</b>이 생겨 Modulith 검사가 막았다.
 * {@code timeline} 이 이미 {@code dispute} 의 사실을 모으고 있기 때문이다.
 *
 * <p>그래서 주문번호로 받는다. 분쟁 화면에서는 그 분쟁의 주문번호로 이 문을 두드린다.
 *
 * <p><b>제출은 사람이 따로 한다</b>({@code POST /api/v1/admin/disputes/{id}/evidence}).
 * 승소율을 재 본 적이 없어 자동 제출은 하지 않는다.
 */
@RestController
@RequestMapping("/api/v1/admin/orders")
class DisputeEvidenceAdminController {

    private final DisputeEvidenceService service;

    DisputeEvidenceAdminController(DisputeEvidenceService service) {
        this.service = service;
    }

    @GetMapping("/{orderNo}/dispute-evidence")
    ResponseEntity<DisputeEvidence> draft(@PathVariable String orderNo) {
        return service.assemble(orderNo)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
