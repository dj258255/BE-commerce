package com.beomsu.becommerce.assist.residual;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 자동 확정 승격 판정. <b>조건이 글로만 있고 재는 기구가 없던 것</b>을 코드로 옮긴 자리다.
 */
@DisplayName("자동 확정 승격 판정 — 표본과 오류율")
class AutomationReadinessTest {

    private AutomationReadiness with(List<Object[]> rows, int minSamples, double maxRate) {
        var repo = mock(SuggestionOutcomeRepository.class);
        when(repo.tallyByCause()).thenReturn(rows);
        var svc = new AutomationReadiness(repo);
        ReflectionTestUtils.setField(svc, "minSamples", minSamples);
        ReflectionTestUtils.setField(svc, "maxErrorRate", maxRate);
        return svc;
    }

    private Object[] row(String cause, boolean blind, String outcome, long n) {
        return new Object[]{cause, blind, outcome, n};
    }

    @Test
    @DisplayName("표본이 모자라면 오류율이 0이어도 안 올린다")
    void refusesWhenSamplesTooFew() {
        var out = with(List.<Object[]>of(row("INTERNAL_RECORD_LOST", true, "accepted", 10L)), 30, 0.05).assess();

        assertThat(out).singleElement().satisfies(v -> {
            assertThat(v.errorRate()).isZero();
            assertThat(v.ready()).isFalse();
            assertThat(v.reason()).contains("표본 10건");
        });
    }

    @Test
    @DisplayName("표본이 차고 오류율이 한도 안이면 올릴 수 있다고 답한다")
    void readyWhenSamplesAndRateOk() {
        var out = with(List.<Object[]>of(row("INTERNAL_RECORD_LOST", true, "accepted", 49L),
                               row("INTERNAL_RECORD_LOST", true, "rejected", 1L)), 30, 0.05).assess();

        assertThat(out).singleElement().satisfies(v -> {
            assertThat(v.samples()).isEqualTo(50);
            assertThat(v.errorRate()).isEqualTo(0.02);
            assertThat(v.ready()).isTrue();
            // 나머지 두 조건은 건별이라 여기서 안 본다는 것을 근거에 남긴다
            assertThat(v.reason()).contains("증거·금액 조건은 건별");
        });
    }

    @Test
    @DisplayName("오류율이 한도를 넘으면 표본이 충분해도 안 올린다")
    void refusesWhenErrorRateTooHigh() {
        var out = with(List.<Object[]>of(row("FEE_CALCULATION_DIFF", true, "accepted", 40L),
                               row("FEE_CALCULATION_DIFF", true, "rejected", 10L)), 30, 0.05).assess();

        assertThat(out).singleElement().satisfies(v -> {
            assertThat(v.errorRate()).isEqualTo(0.2);
            assertThat(v.ready()).isFalse();
            assertThat(v.reason()).contains("한도");
        });
    }

    @Test
    @DisplayName("보여준 뒤 고른 표본은 안 센다 — 앵커링이 섞이면 모델 정확도로 못 읽는다")
    void ignoresNonBlindSamples() {
        var out = with(List.<Object[]>of(row("INTERNAL_RECORD_LOST", false, "accepted", 100L)), 30, 0.05).assess();

        assertThat(out).as("가린 표본이 없으므로 판정할 유형이 없다").isEmpty();
    }

    @Test
    @DisplayName("기권은 분모에서 뺀다 — 맞고 틀림이 아니다")
    void abstentionIsNotCounted() {
        var out = with(List.<Object[]>of(row("INTERNAL_RECORD_LOST", true, "accepted", 30L),
                               row("INTERNAL_RECORD_LOST", true, "abstained", 70L)), 30, 0.05).assess();

        assertThat(out).singleElement().satisfies(v -> {
            assertThat(v.samples()).as("기권 70건은 분모에 안 든다").isEqualTo(30);
            assertThat(v.ready()).isTrue();
        });
    }
}
