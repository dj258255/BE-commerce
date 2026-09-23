package com.beomsu.becommerce.fraud.web;

import com.beomsu.becommerce.fraud.model.LabelledScoreReport;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 섀도 점수를 차지백 라벨로 채점한 결과.
 *
 * <p><b>이 화면이 켤지 정하는 자리다.</b> docs/27 5-2 절의 조건 A·B 는 우리가 만든 코퍼스에서
 * 잰 값이라 실 트래픽에서도 그렇다는 근거가 못 된다. 여기 값이 그 자리를 메운다.
 *
 * <p>표본이 얇으면 {@code usable} 이 거짓이다. 비율이 좋아 보여도 그때는 근거로 쓰면 안 된다.
 */
@RestController
class FraudModelReportAdminController {

    private final LabelledScoreReport report;

    FraudModelReportAdminController(LabelledScoreReport report) {
        this.report = report;
    }

    @GetMapping("/api/v1/admin/fds/model-report")
    ResponseEntity<LabelledScoreReport.Report> report(
            @RequestParam(defaultValue = "90") int days,
            @RequestParam(defaultValue = "500") int limit) {
        return ResponseEntity.ok(report.scoreAgainstDisputes(days, limit));
    }
}
