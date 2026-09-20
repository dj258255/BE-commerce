package com.beomsu.becommerce.assist.web;

import com.beomsu.becommerce.assist.incident.IncidentAnalysisPort;
import com.beomsu.becommerce.assist.incident.IncidentCause;
import com.beomsu.becommerce.assist.incident.IncidentDiagnosis;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 장애 분석 창구가 <b>실제로 도는지</b>. 이 문이 없어서 기능이 아무 데서도 안 불리고 있었다.
 */
@DisplayName("장애 분석 창구 — 제안만 하고 못 고르면 비운다")
class IncidentAnalysisAdminControllerTest {

    private IncidentAnalysisAdminController controllerReturning(Optional<IncidentDiagnosis> answer) {
        return new IncidentAnalysisAdminController(new IncidentAnalysisPort() {
            @Override
            public Optional<IncidentDiagnosis> diagnose(String logs) {
                return answer;
            }

            @Override
            public String name() {
                return "stub";
            }
        });
    }

    @Test
    @DisplayName("원인을 고르면 근거 인용과 출처를 함께 낸다")
    void returnsDiagnosisWithEvidence() {
        var diagnosis = new IncidentDiagnosis(IncidentCause.DB_TIMEOUT,
                "Connection is not available, request timed out", IncidentDiagnosis.Source.RULE);

        var res = controllerReturning(Optional.of(diagnosis))
                .analyze(new IncidentAnalysisAdminController.AnalyzeRequest("아무 로그"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().cause()).isEqualTo(IncidentCause.DB_TIMEOUT);
        // 규칙이 낸 것과 모델이 낸 것은 화면에서 구별돼야 한다.
        assertThat(res.getBody().source()).isEqualTo(IncidentDiagnosis.Source.RULE);
    }

    @Test
    @DisplayName("못 고르면 204 — 억지로 찍은 원인은 확인하는 사람의 일을 늘린다")
    void returnsNoContentWhenUndecided() {
        var res = controllerReturning(Optional.empty())
                .analyze(new IncidentAnalysisAdminController.AnalyzeRequest("아무 로그"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    @DisplayName("빈 로그는 400")
    void rejectsBlankLogs() {
        assertThat(controllerReturning(Optional.empty())
                .analyze(new IncidentAnalysisAdminController.AnalyzeRequest("  "))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(controllerReturning(Optional.empty())
                .analyze(new IncidentAnalysisAdminController.AnalyzeRequest(null))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("너무 긴 본문은 잘라서 받지 않고 거절한다 — 어디가 잘렸는지 모르면 근거를 못 짚는다")
    void rejectsOversizedBody() {
        String huge = "x".repeat(100_001);

        var res = controllerReturning(Optional.empty())
                .analyze(new IncidentAnalysisAdminController.AnalyzeRequest(huge));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }
}
