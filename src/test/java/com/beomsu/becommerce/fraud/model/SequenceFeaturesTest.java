package com.beomsu.becommerce.fraud.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 피처 추출을 고정한다. <b>모델보다 이쪽이 먼저 맞아야 한다.</b>
 *
 * <p>가중치가 틀리면 성적이 나빠져 눈에 띈다. 피처가 틀리면 모델이 그 틀린 값에 맞춰 학습해서
 * <b>성적은 멀쩡한데 뜻이 다른 것</b>을 배운다. 그건 평가로 안 잡힌다.
 */
@DisplayName("시퀀스 피처 — 규칙이 못 보는 축을 숫자로 만든다")
class SequenceFeaturesTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final long THRESHOLD = 1_000_000L;

    private static Instant at(int hour, int minute) {
        return ZonedDateTime.of(2026, 6, 1, hour, minute, 0, 0, KST).toInstant();
    }

    private static TxnRecord tx(long amount, int hour, int minute, String device, String ip) {
        return new TxnRecord(amount, at(hour, minute), device, ip);
    }

    @Test
    @DisplayName("창이 비면 이번 건 하나로 본다 — 0 벡터를 주면 첫 거래가 전부 한쪽으로 쏠린다")
    void emptyWindowFallsBackToCurrent() {
        var now = tx(50_000, 14, 0, "d", "i");

        var f = SequenceFeatures.of(List.of(), now, THRESHOLD);

        assertThat(f.windowCount()).isGreaterThan(0);
        assertThat(f.amountToMedian()).as("과거가 없으면 배수는 1 이다").isEqualTo(0.1);
        assertThat(f.escalation()).isZero();
    }

    @Test
    @DisplayName("임계를 넘긴 건은 near 에 안 센다 — 그건 규칙이 이미 잡는다")
    void overThresholdIsNotNear() {
        var over = tx(1_200_000, 14, 0, "d", "i");
        var near = tx(950_000, 14, 5, "d", "i");

        var f = SequenceFeatures.of(List.of(over, near), near, THRESHOLD);

        assertThat(f.nearThresholdRatio())
                .as("두 건 중 임계 밑 고액은 하나다")
                .isEqualTo(0.5);
    }

    @Test
    @DisplayName("소액 시도를 센다 — 카드가 살아 있는지 떠보는 자리")
    void countsMicroProbes() {
        var probes = List.of(
                tx(100, 3, 0, "d", "i"), tx(300, 3, 3, "d", "i"), tx(500, 3, 6, "d", "i"));
        var main = tx(800_000, 3, 20, "d", "i");

        var f = SequenceFeatures.of(
                List.of(probes.get(0), probes.get(1), probes.get(2), main), main, THRESHOLD);

        assertThat(f.microCount()).isEqualTo(0.75);
        assertThat(f.nightRatio()).as("네 건 다 새벽이다").isEqualTo(1.0);
    }

    @Test
    @DisplayName("금액이 매번 오르면 escalation 이 1 이다")
    void monotonicIncreaseIsFullEscalation() {
        var last = tx(400_000, 14, 30, "d", "i");
        var f = SequenceFeatures.of(List.of(
                tx(20_000, 14, 0, "d", "i"),
                tx(90_000, 14, 10, "d", "i"),
                tx(200_000, 14, 20, "d", "i"),
                last), last, THRESHOLD);

        assertThat(f.escalation()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("기기를 매번 바꾸면 churn 이 1 이다 — 기기 속도 규칙은 키마다 세서 못 본다")
    void rotatingDevicesGiveFullChurn() {
        var last = tx(300_000, 14, 30, "d3", "i");
        var f = SequenceFeatures.of(List.of(
                tx(300_000, 14, 0, "d0", "i"),
                tx(300_000, 14, 10, "d1", "i"),
                tx(300_000, 14, 20, "d2", "i"),
                last), last, THRESHOLD);

        assertThat(f.deviceChurn()).isEqualTo(1.0);
        assertThat(f.ipChurn()).as("IP 는 하나라 0.25 다").isEqualTo(0.25);
    }

    @Test
    @DisplayName("이번 금액이 평소의 몇 배인지를 본다 — 그 카드의 기준선이 규칙에는 없다")
    void amountIsComparedToPastMedian() {
        var spike = tx(500_000, 14, 30, "d", "i");
        var f = SequenceFeatures.of(List.of(
                tx(50_000, 14, 0, "d", "i"),
                tx(50_000, 14, 10, "d", "i"),
                tx(50_000, 14, 20, "d", "i"),
                spike), spike, THRESHOLD);

        // 과거 중앙값 50,000 대비 10배 → 포화점이 10 이라 1.0
        assertThat(f.amountToMedian()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("모든 피처가 0~1 안에 있다 — 단위가 섞이면 가중치가 크기 차이를 흡수한다")
    void everyFeatureIsBounded() {
        var last = tx(999_999, 2, 30, "dX", "iX");
        var f = SequenceFeatures.of(List.of(
                tx(100, 2, 0, "d0", "i0"),
                tx(900_000, 2, 10, "d1", "i1"),
                tx(950_000, 2, 20, "d2", "i2"),
                last), last, THRESHOLD);

        for (double v : f.toArray()) {
            assertThat(v).isBetween(0.0, 1.0);
        }
        assertThat(f.toArray()).hasSameSizeAs(SequenceFeatures.NAMES.toArray());
    }
}
