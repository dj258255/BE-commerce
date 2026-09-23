package com.beomsu.becommerce.order.web;

import com.beomsu.becommerce.SecurityConfig;
import com.beomsu.becommerce.order.compensation.CompensationTaskView;
import com.beomsu.becommerce.order.compensation.CompensationStatus;
import com.beomsu.becommerce.order.compensation.CompensationAdminService;
import com.beomsu.becommerce.order.compensation.CompensationAdminService;
import com.beomsu.becommerce.order.compensation.CompensationStatus;
import com.beomsu.becommerce.order.compensation.CompensationTaskView;
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
 * 보상 태스크 운영 어드민 REST 컨트롤러.
 *
 * <p>인가는 {@code SecurityConfig}에서 {@code /api/v1/admin/**}에 ROLE_ADMIN을 요구해 강제한다.
 * 상태를 바꾸는 재시도는 호출자(principal)를 감사 로그로 남긴다(기존 DLQ 어드민과 같은 결).
 */
@RestController
@RequestMapping("/api/v1/admin/compensations")
@RequiredArgsConstructor
class CompensationAdminController {

    private static final Logger audit = LoggerFactory.getLogger("AUDIT");

    private final CompensationAdminService adminService;

    @GetMapping
    Page<CompensationTaskView> list(@RequestParam(defaultValue = "FAILED") CompensationStatus status,
                                    @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable pageable) {
        return adminService.list(status, pageable);
    }

    @PostMapping("/{id}/retry")
    Map<String, Object> retry(@PathVariable Long id, Principal caller) {
        String who = caller != null ? caller.getName() : "unknown";
        audit.info("보상 태스크 수동 재시도 요청 by={} taskId={}", who, id);
        boolean ok = adminService.retry(id);
        audit.info("보상 태스크 수동 재시도 결과 by={} taskId={} retried={}", who, id, ok);
        return Map.of("id", id, "retried", ok);
    }
}
