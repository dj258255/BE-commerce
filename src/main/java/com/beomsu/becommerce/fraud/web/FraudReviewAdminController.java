package com.beomsu.becommerce.fraud.web;

import com.beomsu.becommerce.SecurityConfig;
import com.beomsu.becommerce.fraud.review.FraudReviewAdminService;
import com.beomsu.becommerce.fraud.review.FraudReviewStatus;
import com.beomsu.becommerce.fraud.review.FraudReviewView;
import com.beomsu.becommerce.fraud.review.RuleFalsePositive;
import com.beomsu.becommerce.fraud.review.RuleFalsePositiveService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;

/**
 * FDS 심사 큐 백오피스 어드민 REST 컨트롤러.
 *
 * <p>인가는 {@code SecurityConfig}에서 {@code /api/v1/admin/**}에 ROLE_ADMIN을 요구해 강제한다.
 * 상태를 바꾸는 승인/거부는 호출자(principal)를 감사 로그로 남긴다(운영에선 maker-checker·감사
 * 테이블로 강화). 거부는 카드 블랙리스트 등록을 유발하므로 특히 추적이 중요하다.
 */
@RestController
@RequestMapping("/api/v1/admin/fraud-reviews")
@RequiredArgsConstructor
class FraudReviewAdminController {

    private static final Logger audit = LoggerFactory.getLogger("AUDIT");

    private final FraudReviewAdminService adminService;
    private final RuleFalsePositiveService ruleFalsePositiveService;
    private final com.beomsu.becommerce.fraud.FraudReviewFactsPort facts;

    /** 상태별 심사 항목 목록(기본 PENDING = 미결 건). */
    /**
     * 심사 큐. {@code order=risk} 면 모델 점수가 높은 것부터 준다.
     *
     * <p><b>집합은 규칙이 정하고 순서만 모델이 정한다.</b> 어느 순서로 보든 큐에 든 건은
     * 같다. 그래서 모델을 꺼도 심사자가 볼 건이 줄지 않는다.
     *
     * <p><b>기본이 {@code id} 순인 이유</b>: 지금까지 심사자가 보던 순서다. 기본값을 바꾸면
     * 켠 것을 아무도 모르는 채로 화면이 달라진다. 켰다는 것을 아는 상태에서 골라 쓰게 한다.
     */
    @GetMapping
    Page<FraudReviewView> list(@RequestParam(defaultValue = "PENDING") FraudReviewStatus status,
                               @RequestParam(defaultValue = "id") String order,
                               @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable pageable) {
        if ("risk".equalsIgnoreCase(order)) {
            return adminService.listByRisk(status, pageable);
        }
        return adminService.list(status, pageable);
    }

    /**
     * 심사 한 건의 사실 묶음 — 발동 규칙과 그 규칙의 최근 성적, 같은 카드의 지난 심사.
     *
     * <p><b>블라인드 비교가 이걸 필요로 한다.</b> 심사자는 초안을 보기 전에 사실만 보고 자기
     * 메모를 써야 하는데, 그 사실을 볼 창구가 없으면 초안부터 열게 된다. 그러면 사람 답이
     * 초안을 닮아 두 편집률의 차이가 방식 차이가 아니게 된다.
     *
     * <p>초안을 안 만든다. 여기서 초안까지 주면 이 호출 하나로 순서가 무너진다.
     */
    @GetMapping("/{id}/facts")
    ResponseEntity<com.beomsu.becommerce.fraud.FraudReviewFacts> facts(@PathVariable long id) {
        return facts.factsOf(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 승인(정상 거래로 확인). */
    @PostMapping("/{id}/approve")
    FraudReviewView approve(@PathVariable Long id, Principal caller) {
        String who = caller != null ? caller.getName() : "unknown";
        audit.info("FDS 심사 승인 요청 by={} reviewId={}", who, id);
        FraudReviewView view = adminService.approve(id, who);
        audit.info("FDS 심사 승인 결과 by={} reviewId={} status={}", who, id, view.status());
        return view;
    }

    /** 거부(부정 거래로 확인 → 카드 블랙리스트 등록). */
    @PostMapping("/{id}/reject")
    FraudReviewView reject(@PathVariable Long id, Principal caller) {
        String who = caller != null ? caller.getName() : "unknown";
        audit.info("FDS 심사 거부 요청 by={} reviewId={}", who, id);
        FraudReviewView view = adminService.reject(id, who);
        audit.info("FDS 심사 거부 결과 by={} reviewId={} status={}", who, id, view.status());
        return view;
    }

    /**
     * 규칙별 오탐률. <b>어느 규칙을 조일지</b>를 고르는 화면이 쓴다.
     *
     * <p>전체 오탐률 알림이 울렸을 때 여는 자리다. 알림은 "정상 거래를 잡고 있다"까지만
     * 말하고, 다섯 규칙 중 어느 것인지는 여기서 본다.
     *
     * <p>기준을 넘긴 것만 주지 않는다. 목록이 비어 있는 것과 다 멀쩡한 것은 다르다.
     */
    @GetMapping("/rule-false-positives")
    List<RuleFalsePositive> ruleFalsePositives() {
        return ruleFalsePositiveService.byRule();
    }
}
