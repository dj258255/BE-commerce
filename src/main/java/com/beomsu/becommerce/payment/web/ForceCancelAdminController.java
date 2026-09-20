package com.beomsu.becommerce.payment.web;

import com.beomsu.becommerce.SecurityConfig;
import com.beomsu.becommerce.payment.forcecancel.ForceCancelView;
import com.beomsu.becommerce.payment.forcecancel.ForceCancelStatus;
import com.beomsu.becommerce.payment.forcecancel.ForceCancelService;
import com.beomsu.becommerce.payment.forcecancel.ForceCancelService;
import com.beomsu.becommerce.payment.forcecancel.ForceCancelStatus;
import com.beomsu.becommerce.payment.forcecancel.ForceCancelView;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

/**
 * 강제취소 maker-checker 운영 어드민 REST 컨트롤러.
 *
 * <p>인가는 {@code SecurityConfig}에서 {@code /api/v1/admin/**}에 ROLE_ADMIN을 요구해 강제한다.
 * 요청(maker)·승인(checker)·거부는 모두 호출자(principal)를 감사 로그로 남긴다. 요청자≠승인자는
 * 도메인이 강제하므로, admin이 요청하고 admin2가 승인하는 2인 흐름으로만 실제 취소가 실행된다.
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
class ForceCancelAdminController {

    private static final Logger audit = LoggerFactory.getLogger("AUDIT");

    private final ForceCancelService service;

    /** 강제취소 요청 생성(maker). */
    @PostMapping("/payments/{id}/force-cancel")
    ForceCancelView request(@PathVariable Long id, @RequestBody ForceCancelRequestBody body, Principal caller) {
        String who = caller != null ? caller.getName() : "unknown";
        audit.info("강제취소 요청 by={} paymentId={} cancelAmount={} reason={}",
                who, id, body.cancelAmount(), body.reason());
        return service.request(id, body.cancelAmount(), body.reason(), who);
    }

    /** 승인(checker) = 실제 취소 실행. */
    @PostMapping("/force-cancels/{requestId}/approve")
    ForceCancelView approve(@PathVariable Long requestId, Principal caller) {
        String who = caller != null ? caller.getName() : "unknown";
        audit.info("강제취소 승인(=실행) 요청 by={} requestId={}", who, requestId);
        ForceCancelView view = service.approve(requestId, who);
        audit.info("강제취소 승인 결과 by={} requestId={} status={}", who, requestId, view.status());
        return view;
    }

    /** 거부(checker). */
    @PostMapping("/force-cancels/{requestId}/reject")
    ForceCancelView reject(@PathVariable Long requestId, Principal caller) {
        String who = caller != null ? caller.getName() : "unknown";
        audit.info("강제취소 거부 요청 by={} requestId={}", who, requestId);
        ForceCancelView view = service.reject(requestId, who);
        audit.info("강제취소 거부 결과 by={} requestId={} status={}", who, requestId, view.status());
        return view;
    }

    /** 상태별 목록(기본 REQUESTED = 미결 건). */
    @GetMapping("/force-cancels")
    Page<ForceCancelView> list(@RequestParam(defaultValue = "REQUESTED") ForceCancelStatus status,
                               @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable pageable) {
        return service.list(status, pageable);
    }

    record ForceCancelRequestBody(long cancelAmount, String reason) {
    }
}
