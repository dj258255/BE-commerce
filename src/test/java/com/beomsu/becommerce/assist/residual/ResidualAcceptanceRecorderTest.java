package com.beomsu.becommerce.assist.residual;

import com.beomsu.becommerce.reconciliation.ReconciliationResolvedEvent;
import com.beomsu.becommerce.reconciliation.ResolveCause;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 채택률·소요 시간·blind 비교가 실제로 남는지 고정한다.
 *
 * <p><b>채택률 하나만 재면 안 되는 게 요점이다.</b> 사람이 제안을 보고 확정하므로
 * 앵커링이 섞인다. 감춘 표본과 나눠 세지 않으면 "제안 덕분"과 "원래 그랬을 것"을
 * 가를 수 없다. 그래서 blind 태그가 붙는지를 함께 본다.
 */
class ResidualAcceptanceRecorderTest {

    private ResidualSuggestionLog log;
    private MeterRegistry registry;
    private ResidualAcceptanceRecorder recorder;

    @BeforeEach
    void setUp() {
        log = new ResidualSuggestionLog();
        registry = new SimpleMeterRegistry();
        recorder = new ResidualAcceptanceRecorder(log, registry, org.mockito.Mockito.mock(SuggestionOutcomeRepository.class));
    }

    private void resolve(long reconId, String chosen) {
        recorder.onResolved(new ReconciliationResolvedEvent(
                reconId, "ORD-" + reconId, chosen, "admin", Instant.now()));
    }

    private double counted(String outcome, String blind) {
        var c = registry.find("assist.residual.accepted")
                .tag("outcome", outcome).tag("blind", blind).counter();
        return c == null ? 0 : c.count();
    }

    @Test
    @DisplayName("보여준 제안을 사람이 그대로 골랐으면 채택으로 센다")
    void countsAcceptance() {
        log.record(1L, ResolveCause.PG_FILE_DELAY, true);

        resolve(1L, "PG_FILE_DELAY");

        assertThat(counted("accepted", "false")).isEqualTo(1);
    }

    @Test
    @DisplayName("보여줬는데 사람이 다른 걸 골랐으면 반려로 센다")
    void countsRejection() {
        log.record(2L, ResolveCause.PG_FILE_DELAY, true);

        resolve(2L, "TIMEZONE_BOUNDARY");

        assertThat(counted("rejected", "false")).isEqualTo(1);
    }

    @Test
    @DisplayName("감춘 표본은 blind=true 로 따로 센다 — 이게 비교군이다")
    void separatesBlindSample() {
        log.record(3L, ResolveCause.DUPLICATE_RECORD, false);   // 만들었지만 안 보여줬다

        resolve(3L, "DUPLICATE_RECORD");

        // 사람이 같은 답을 냈지만 <제안을 안 보고> 냈다. 앵커링이 없다.
        assertThat(counted("accepted", "true")).isEqualTo(1);
        assertThat(counted("accepted", "false")).isZero();
    }

    @Test
    @DisplayName("모델이 기권했으면 채택도 반려도 아니다")
    void abstentionIsNeither() {
        log.record(4L, null, true);

        resolve(4L, "OTHER");

        assertThat(counted("abstained", "false")).isEqualTo(1);
        assertThat(counted("accepted", "false")).isZero();
        assertThat(counted("rejected", "false")).isZero();
    }

    @Test
    @DisplayName("후보를 부른 적 없는 확정은 집계에서 가른다")
    void noSuggestionIsTracked() {
        resolve(5L, "FEE_CALCULATION_DIFF");

        assertThat(counted("no_suggestion", "-")).isEqualTo(1);
    }

    @Test
    @DisplayName("확정까지 걸린 시간을 남긴다")
    void recordsLatency() {
        log.record(6L, ResolveCause.PG_FILE_DELAY, true);

        resolve(6L, "PG_FILE_DELAY");

        assertThat(registry.find("assist.residual.resolve.latency")
                .tag("blind", "false").timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("한 번 맞춰 본 기록은 지운다 — 같은 건이 두 번 세어지지 않는다")
    void entryIsConsumedOnce() {
        log.record(7L, ResolveCause.PG_FILE_DELAY, true);

        resolve(7L, "PG_FILE_DELAY");
        resolve(7L, "PG_FILE_DELAY");

        assertThat(counted("accepted", "false")).isEqualTo(1);
        assertThat(counted("no_suggestion", "-")).isEqualTo(1);
        assertThat(log.size()).isZero();
    }
}
