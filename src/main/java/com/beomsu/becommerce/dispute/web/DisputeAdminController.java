package com.beomsu.becommerce.dispute.web;

import com.beomsu.becommerce.SecurityConfig;
import com.beomsu.becommerce.dispute.internal.DisputeException;
import com.beomsu.becommerce.dispute.internal.DisputeService;
import com.beomsu.becommerce.dispute.internal.DisputeView;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;

/**
 * 분쟁/차지백 백오피스 어드민 REST 컨트롤러.
 *
 * <p>인가는 {@code SecurityConfig}에서 {@code /api/v1/admin/**}에 ROLE_ADMIN을 요구해 강제한다.
 * 운영자가 분쟁 목록/상세를 보고, 증빙을 제출하거나 승패를 확정한다. 상태를 바꾸는 액션(증빙/확정)은
 * 호출자(principal)를 감사 로그로 남긴다 — 특히 패소 확정은 원장 역분개를 유발하므로 추적이 중요하다.
 */
@RestController
@RequestMapping("/api/v1/admin/disputes")
@RequiredArgsConstructor
class DisputeAdminController {

    private static final Logger audit = LoggerFactory.getLogger("AUDIT");

    private final DisputeService disputeService;

    /** 최근 분쟁 목록. */
    @GetMapping
    List<DisputeView> list() {
        return disputeService.recent();
    }

    /** 분쟁 상세. */
    @GetMapping("/{id}")
    DisputeView detail(@PathVariable Long id) {
        return disputeService.detail(id);
    }

    /** 증빙 제출(OPEN → EVIDENCE_SUBMITTED). */
    @PostMapping("/{id}/evidence")
    DisputeView submitEvidence(@PathVariable Long id, @RequestBody EvidenceRequest request, Principal caller) {
        String who = caller != null ? caller.getName() : "unknown";
        audit.info("분쟁 증빙 제출 by={} disputeId={}", who, id);
        return disputeService.submitEvidence(id, request.memo());
    }

    /** 승패 확정(WON/LOST). LOST면 원장 역분개 이벤트가 발행된다. */
    @PostMapping("/{id}/resolve")
    DisputeView resolve(@PathVariable Long id, @RequestBody ResolveRequest request, Principal caller) {
        String who = caller != null ? caller.getName() : "unknown";
        // outcome을 엄격히 파싱한다 — "WON" 아니면 뭐든 LOST(비가역 역분개)로 처리하는 기본값은 위험하다.
        // 오타·null·"WIN"이 돈 움직이는 쪽으로 흐르지 않게, WON/LOST 외에는 400으로 거부한다.
        String outcome = request.outcome() == null ? "" : request.outcome().trim().toUpperCase();
        if (!outcome.equals("WON") && !outcome.equals("LOST")) {
            throw new DisputeException("INVALID_DISPUTE_OUTCOME",
                    "outcome은 WON 또는 LOST여야 합니다: " + request.outcome());
        }
        audit.info("분쟁 승패 확정 by={} disputeId={} outcome={}", who, id, outcome);
        DisputeView view = disputeService.resolve(id, outcome.equals("WON"));
        audit.info("분쟁 승패 확정 결과 by={} disputeId={} status={}", who, id, view.status());
        return view;
    }

    /** 증빙 제출 요청 — 제출한 증빙 요약 메모. */
    record EvidenceRequest(String memo) {
    }

    /** 승패 확정 요청 — outcome은 "WON" 또는 "LOST". */
    record ResolveRequest(String outcome) {
    }
}
