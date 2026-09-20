package com.beomsu.becommerce.payment.web;

import com.beomsu.becommerce.SecurityConfig;
import com.beomsu.becommerce.payment.internal.PaymentAdminService;
import com.beomsu.becommerce.payment.internal.PaymentSyncView;
import com.beomsu.becommerce.payment.internal.UnknownPaymentView;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.Map;

/**
 * 미확정(UNKNOWN) 결제 복구 운영 어드민 REST 컨트롤러.
 *
 * <p>인가는 {@code SecurityConfig}에서 {@code /api/v1/admin/**}에 ROLE_ADMIN을 요구해 강제한다.
 * 상태를 바꾸는 복구는 호출자(principal)를 감사 로그로 남긴다(기존 DLQ 어드민과 같은 결).
 */
@RestController
@RequestMapping("/api/v1/admin/payments")
@RequiredArgsConstructor
class PaymentRecoveryAdminController {

    private static final Logger audit = LoggerFactory.getLogger("AUDIT");

    private final PaymentAdminService adminService;

    @GetMapping("/unknown")
    Page<UnknownPaymentView> listUnknown(@PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable pageable) {
        return adminService.listUnknown(pageable);
    }

    @PostMapping("/recover")
    Map<String, Object> recover(Principal caller) {
        String who = caller != null ? caller.getName() : "unknown";
        audit.info("미확정 결제 수동 복구 요청 by={}", who);
        int recovered = adminService.recover();
        audit.info("미확정 결제 수동 복구 결과 by={} recovered={}", who, recovered);
        return Map.of("recovered", recovered);
    }

    @PostMapping("/{id}/sync")
    PaymentSyncView sync(@PathVariable Long id, Principal caller) {
        String who = caller != null ? caller.getName() : "unknown";
        audit.info("단건 결제 PG 강제 동기화 요청 by={} paymentId={}", who, id);
        PaymentSyncView view = adminService.sync(id);
        audit.info("단건 결제 PG 강제 동기화 결과 by={} paymentId={} status={}", who, id, view.status());
        return view;
    }
}
