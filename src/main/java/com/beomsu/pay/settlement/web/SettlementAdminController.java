package com.beomsu.pay.settlement.web;

import com.beomsu.pay.SecurityConfig;
import com.beomsu.pay.settlement.internal.Settlement;
import com.beomsu.pay.settlement.internal.SettlementAdminService;
import com.beomsu.pay.settlement.internal.SettlementView;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import com.beomsu.pay.settlement.internal.SettlementCompositionView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.time.LocalDate;

/**
 * 정산 운영 어드민 REST 컨트롤러(정산 조회 + 지급 확정 + 수동 배치 실행).
 *
 * <p>인가는 {@code SecurityConfig}에서 {@code /api/v1/admin/**}에 ROLE_ADMIN을 요구해 강제한다.
 * 상태를 바꾸는 지급 확정·수동 실행은 호출자(principal)를 감사 로그로 남긴다(다른 어드민과 같은 결).
 */
@RestController
@RequestMapping("/api/v1/admin/settlements")
@RequiredArgsConstructor
class SettlementAdminController {

    private static final Logger audit = LoggerFactory.getLogger("AUDIT");

    private final SettlementAdminService adminService;

    @GetMapping
    Page<SettlementView> list(@PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable pageable) {
        return adminService.list(pageable);
    }

    /**
     * 정산 한 건이 언제 번 돈으로 이뤄졌는지. 읽기만 하고 금액은 바꾸지 않는다(ADR-023).
     */
    @GetMapping("/{id}/composition")
    SettlementCompositionView composition(@PathVariable Long id) {
        return adminService.composition(id);
    }

    @PostMapping("/{id}/payout")
    SettlementView payout(@PathVariable Long id, Principal caller) {
        String who = caller != null ? caller.getName() : "unknown";
        audit.info("정산 지급 확정 요청 by={} settlementId={}", who, id);
        SettlementView view = adminService.confirmPayout(id);
        audit.info("정산 지급 확정 결과 by={} settlementId={} status={}", who, id, view.status());
        return view;
    }

    /**
     * 정산 배치를 수동 실행한다(데모·수동 운영용). 재실행이거나 대상 CONFIRMED 항목이 없어 정산이
     * 만들어지지 않으면 204 No Content로 그 사실을 알린다.
     */
    @PostMapping("/run")
    ResponseEntity<SettlementView> run(
            @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            Principal caller) {
        String who = caller != null ? caller.getName() : "unknown";
        audit.info("정산 배치 수동 실행 요청 by={} date={}", who, date);
        Settlement settlement = adminService.runSettlement(date);
        if (settlement == null) {
            audit.info("정산 배치 수동 실행 결과 by={} date={} created=false(재실행/대상없음)", who, date);
            return ResponseEntity.noContent().build();
        }
        audit.info("정산 배치 수동 실행 결과 by={} date={} created=true settlementId={} net={}",
                who, date, settlement.getId(), settlement.getNetAmount());
        return ResponseEntity.ok(SettlementView.from(settlement));
    }
}
