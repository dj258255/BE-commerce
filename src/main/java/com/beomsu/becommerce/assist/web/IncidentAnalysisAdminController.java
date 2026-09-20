package com.beomsu.becommerce.assist.web;

import com.beomsu.becommerce.assist.incident.IncidentAnalysisPort;
import com.beomsu.becommerce.assist.incident.IncidentDiagnosis;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 장애 로그에서 원인 유형을 고르는 창구. <b>제안만 한다.</b>
 *
 * <p><b>이 문이 없었다.</b> 규칙과 모델을 만들고 실 로그 12건으로 재고 기본 구성까지 정했는데,
 * 정작 부르는 곳이 하나도 없었다. 평가 테스트에서만 돌고 있었다. 구성을 정한 것과 기능이
 * 도는 것은 다른 일인데 <b>기본값을 바꿔 놓고 켰다고 적고 있었다.</b>
 *
 * <p><b>붙이는 것은 아무것도 없다.</b> 로그를 받아 원인 후보 하나와 그 근거가 된 인용 한 줄을
 * 돌려줄 뿐이고, 장부나 결제 상태는 건드리지 않는다. 확정은 사람이 한다.
 *
 * <p><b>로그는 신뢰하지 않는다.</b> 본문은 외부에서 흘러든 문자열이 섞일 수 있는 자리다
 * (PG 응답, 사용자 입력이 로그에 찍힌다). 어댑터가 프롬프트에 넣기 전에 개행과 제어문자를
 * 눕히고 길이를 자른다. 못 고르면 <b>204</b>다 — 억지로 찍은 원인은 확인하는 사람의 일을 늘린다.
 */
@RestController
@RequestMapping("/api/v1/admin/incidents")
class IncidentAnalysisAdminController {

    /** 한 번에 받을 로그 길이 상한. 이보다 길면 앞뒤만 남기고 어댑터가 줄인다. */
    private static final int MAX_BODY_CHARS = 100_000;

    private final IncidentAnalysisPort analyzer;

    IncidentAnalysisAdminController(IncidentAnalysisPort analyzer) {
        this.analyzer = analyzer;
    }

    @PostMapping("/analyze")
    ResponseEntity<IncidentDiagnosis> analyze(@RequestBody AnalyzeRequest request) {
        String logs = request.logs();
        if (logs == null || logs.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        if (logs.length() > MAX_BODY_CHARS) {
            // 잘라서 받지 않고 거절한다. 어디가 잘렸는지 모른 채 받은 진단은 근거를 못 짚는다.
            return ResponseEntity.unprocessableEntity().build();
        }
        return analyzer.diagnose(logs)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** @param logs 붙여 넣은 로그 조각. 여러 줄이다 */
    record AnalyzeRequest(String logs) {
    }
}
