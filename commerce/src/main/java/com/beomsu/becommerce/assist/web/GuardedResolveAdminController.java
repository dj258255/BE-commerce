package com.beomsu.becommerce.assist.web;

import com.beomsu.becommerce.assist.resolve.GuardedResolveService;
import com.beomsu.becommerce.assist.resolve.IncompleteEvidenceException;
import com.beomsu.becommerce.reconciliation.ResolveCause;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Map;

/**
 * 자료가 온전한지 <b>서버가 확인한 뒤</b> 대사를 확정한다.
 *
 * <p>대사 모듈의 확정 API 는 타임라인을 못 본다({@code timeline → reconciliation} 순환).
 * 그래서 둘 다 읽을 수 있는 이 모듈에 게이트를 두고, <b>운영 화면은 이 경로로만 확정한다.</b>
 *
 * <p>대사 쪽 원본 API 는 그대로 남아 있다. 타임라인이 아예 없는 건(외부에만 있는 대사 결과)도
 * 닫아야 하기 때문이다. <b>즉 이 게이트는 화면의 우회를 막는 것이지 API 전체를 잠그는 것이
 * 아니다.</b> 거기까지 잠그려면 대사 확정을 이 모듈로 옮겨야 하고, 그건 이번 범위를 넘었다.
 */
@RestController
@RequestMapping("/api/v1/admin/assist/resolve")
@RequiredArgsConstructor
class GuardedResolveAdminController {

    private final GuardedResolveService service;

    @PostMapping("/{reconResultId}")
    ResponseEntity<Void> resolve(@PathVariable long reconResultId,
                                 @Valid @RequestBody Request body,
                                 Principal caller) {
        service.resolve(reconResultId, caller != null ? caller.getName() : "unknown",
                body.cause(), body.note(), body.acknowledgedMissing());
        return ResponseEntity.noContent().build();
    }

    /**
     * 자료가 빠졌는데 확인 표시가 없으면 <b>409</b> 로 거절한다. 400 이 아닌 이유는
     * 입력이 잘못된 것이 아니라 <b>절차를 아직 안 밟은 것</b>이기 때문이다.
     */
    @ExceptionHandler(IncompleteEvidenceException.class)
    ResponseEntity<Map<String, Object>> onIncomplete(IncompleteEvidenceException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("code", e.code(), "message", e.getMessage(), "missing", e.missing()));
    }

    // 주문번호는 안 받는다. 서버가 대사 결과에서 직접 꺼낸다.
    record Request(@NotNull ResolveCause cause,
                   @Size(max = 500) String note,
                   List<String> acknowledgedMissing) {}
}
