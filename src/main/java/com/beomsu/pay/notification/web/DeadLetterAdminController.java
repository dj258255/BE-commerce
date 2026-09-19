package com.beomsu.pay.notification.web;

import com.beomsu.pay.SecurityConfig;
import com.beomsu.pay.notification.consumption.DeadLetterSummary;
import com.beomsu.pay.notification.consumption.DeadLetterView;
import com.beomsu.pay.notification.NotificationAdminService;
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
 * DLQ 백오피스 어드민 REST 컨트롤러.
 *
 * <p>인가는 {@code SecurityConfig}에서 {@code /api/v1/admin/**} 에 ROLE_ADMIN을 요구해 강제한다.
 * 상태를 바꾸는 재처리는 호출자(principal)를 감사 로그로 남긴다(운영에선 maker-checker·감사 테이블로 강화).
 */
@RestController
@RequestMapping("/api/v1/admin/dead-letters")
@RequiredArgsConstructor
class DeadLetterAdminController {

    private static final Logger audit = LoggerFactory.getLogger("AUDIT");

    private final NotificationAdminService adminService;

    @GetMapping
    Page<DeadLetterView> list(@PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable pageable) {
        return adminService.listDeadLetters(pageable);
    }

    /**
     * 복구 상태 요약(격리 건수·최장 대기 시각) — <b>발견</b>의 표면.
     *
     * <p>재처리(복구)를 돌린 뒤 이 값을 다시 보면 "복구 후 검증"이 된다 — 남은 건수가 줄었고
     * 최장 대기가 사라졌는지로 정상 여부를 확인한다(ADR-030).
     */
    @GetMapping("/summary")
    DeadLetterSummary summary() {
        return adminService.summary();
    }

    @PostMapping("/{id}/reprocess")
    Map<String, Object> reprocess(@PathVariable Long id, Principal caller) {
        String who = caller != null ? caller.getName() : "unknown";
        audit.info("DLQ 재처리 요청 by={} deadLetterId={}", who, id);
        boolean ok = adminService.reprocess(id);
        audit.info("DLQ 재처리 결과 by={} deadLetterId={} reprocessed={}", who, id, ok);
        return Map.of("id", id, "reprocessed", ok);
    }
}
