package com.beomsu.becommerce.reconciliation.web;

import com.beomsu.becommerce.SecurityConfig;
import com.beomsu.becommerce.reconciliation.ResolveCause;
import com.beomsu.becommerce.reconciliation.internal.ReconMismatchView;
import com.beomsu.becommerce.reconciliation.internal.ReconRunSummary;
import com.beomsu.becommerce.reconciliation.ReconciliationAdminService;
import com.beomsu.becommerce.reconciliation.ResolveCause;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.Principal;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 정산 대사 운영 어드민 REST 컨트롤러(정산 파일 업로드 대사 + PENDING 조회 + 수기 확정).
 *
 * <p>인가는 {@code SecurityConfig}에서 {@code /api/v1/admin/**}에 ROLE_ADMIN을 요구해 강제한다.
 * 상태를 바꾸는 실행/수기 확정은 호출자(principal)를 감사 로그로 남긴다(기존 DLQ 어드민과 같은 결).
 */
@RestController
@RequestMapping("/api/v1/admin/reconciliations")
@RequiredArgsConstructor
class ReconciliationAdminController {

    private static final Logger audit = LoggerFactory.getLogger("AUDIT");

    private final ReconciliationAdminService adminService;
    private final com.beomsu.becommerce.reconciliation.cause.RulePromotionService rulePromotionService;

    /**
     * PG 정산 파일(CSV)을 업로드해 대사를 실행한다. 결과는 분류별 집계({@link ReconRunSummary})로 응답하고,
     * 불일치는 PENDING 예외 큐로 남아 {@code /mismatches} 조회 → {@code /{id}/resolve} 수기 확정으로 이어진다.
     *
     * <p>{@code tradeDate}는 <b>어느 날짜의 정산 파일인가</b>다. 대사는 날짜 단위 작업이라 이 값이 없으면
     * 범위를 정할 수 없다. 같은 날짜로 다시 올리면 그 날의 판정을 갈아끼운다(재실행 멱등).
     */
    @PostMapping("/run")
    ReconRunSummary run(@RequestParam("tradeDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate tradeDate,
                        @RequestParam("file") MultipartFile file, Principal caller) {
        String who = caller != null ? caller.getName() : "unknown";
        audit.info("정산 파일 대사 실행 요청 by={} tradeDate={} filename={} size={}",
                who, tradeDate, file.getOriginalFilename(), file.getSize());
        ReconRunSummary summary;
        try {
            summary = adminService.run(tradeDate, file.getInputStream());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        audit.info("정산 파일 대사 실행 결과 by={} external={} skipped={} matched={} pending={}",
                who, summary.external(), summary.skipped(), summary.matched(), summary.pending());
        return summary;
    }

    /**
     * 불일치 하나의 <b>원인 후보</b>를 제안한다 (ADR-012).
     *
     * <p>규칙으로 계산한 제안이지 확정이 아니다. {@code resolve}는 여전히 사람이 호출한다.
     * 각 후보에는 근거가 붙어 있어 사람이 검증할 수 있다 — 근거 없는 제안은
     * 확인 비용만 늘린다.
     */
    @GetMapping("/{id}/suggestions")
    java.util.List<com.beomsu.becommerce.reconciliation.CauseSuggestion> suggestions(@PathVariable Long id) {
        return adminService.suggestCauses(id);
    }

    /**
     * 사람이 반복해서 같은 답을 낸 자리를 센다. <b>규칙으로 올릴 후보</b>다.
     *
     * <p>여기서 규칙을 켜지는 않는다. 대사 규칙을 바꾸는 것은 돈 판정을 바꾸는 일이라
     * 근거를 보고 사람이 눌러야 한다. 이 응답은 <b>어디를 볼지</b>만 알려 준다.
     */
    @GetMapping("/rule-candidates")
    java.util.List<com.beomsu.becommerce.reconciliation.cause.RulePromotionCandidate> ruleCandidates() {
        return rulePromotionService.candidates();
    }

    @GetMapping("/mismatches")
    Page<ReconMismatchView> mismatches(@PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable pageable) {
        return adminService.listMismatches(pageable);
    }

    // 확정 엔드포인트는 여기 없다. 자료가 빠진 채로 확정하는 것을 서버가 막아야 하는데
    // 이 모듈은 타임라인을 못 본다(timeline 이 reconciliation 을 이미 의존해 순환이다).
    // 그래서 둘 다 읽을 수 있는 assist 로 옮겼다: POST /api/v1/admin/assist/resolve/{id}
    // 여기 남겨 두면 화면이 게이트를 건너뛸 수 있어서 통째로 뺐다.


    /** 수기 확정 요청 본문. {@code cause}는 필수, {@code note}는 OTHER일 때 도메인이 필수로 강제한다. */
    record ResolveRequest(@NotNull ResolveCause cause, @Size(max = 500) String note) {}
}
