package com.beomsu.becommerce.monitoring;

import com.beomsu.becommerce.testsupport.SharedContainers;
import com.beomsu.becommerce.dispute.internal.Dispute;
import com.beomsu.becommerce.dispute.internal.DisputeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 알림이 <b>진짜 고장에서 실제로 발화 조건을 만족하는지</b>를 끝까지 확인한다.
 *
 * <p><b>왜 여기까지 하나</b>: 지표 이름을 맞추는 것과 알림이 울리는 것은 다른 일이다.
 * 이름이 맞아도 조건식이 틀리면 안 울리고, <b>안 울리는 알림은 잘 도는 알림과 화면에서
 * 구별되지 않는다.</b> 이름은 계약 테스트로 잡았고, 여기서는 조건을 잡는다.
 *
 * <p>이 방식은 장애 모의 훈련에서 "악화되는 지표에 경고가 실제로 뜨는지 미리 테스트한다"는
 * 대목을 혼자 할 수 있는 크기로 줄인 것이다. 사람 여럿이 역할을 나눠 대응을 연습하는 부분은
 * 이 프로젝트에서 흉내 낼 수 없지만, <b>장치가 작동하는지 확인하는 부분은 혼자서도 된다.</b>
 *
 * <p><b>이의제기 기한을 골랐다.</b> 열여덟 알림 중 이것이 결과가 가장 무겁다. 넘기면 다툴 기회가
 * 사라지고 대금이 회수된다. 그리고 넘기는 순간 아무 에러도 안 난다.
 *
 * <p>즉시값 게이지라 스크레이프 한 번으로 조건을 계산할 수 있다. {@code rate()} 를 쓰는
 * 카운터 알림은 시계열이 있어야 해서 이 방법으로 못 잰다 — 그건 한계로 남긴다.
 */
@Tag("integration")
@SpringBootTest
@DisplayName("알림 발화 통합 — 기한 지난 이의제기에 조건이 참이 되는지")
class DisputeDeadlineAlertFiresIntegrationTest {

    /** {@code monitoring/alert-rules.yml} 의 {@code DisputeDeadlineApproaching} 임계. */
    private static final long APPROACHING_SECONDS = 172_800;   // 48시간

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry props) {
        SharedContainers.register(props, "DisputeDeadlineAlertFires");
    }

    @Autowired
    DisputeRepository repository;

    @Autowired
    MeterRegistry registry;

    /**
     * 게이지 값을 읽는다. 이름은 프로메테우스 표기({@code dispute_nearest_deadline_seconds})가
     * 아니라 등록 이름({@code dispute.nearest.deadline.seconds})으로 찾는다.
     *
     * <p><b>여기서 HTTP 스크레이프를 거치지 않는 이유</b>: 노출 이름이 알림과 맞는지는
     * {@link AlertMetricNameContractTest} 가 실제 스크레이프 결과로 대조한다. 이 테스트가 보는 것은
     * <b>실제 DB 상태에서 그 값이 알림 조건을 만족하느냐</b>다. 두 층을 갈라 놓으면 어느 쪽이
     * 깨졌는지가 실패 메시지에서 바로 보인다.
     */
    private double gauge(String name) {
        var g = registry.find(name).gauge();
        assertThat(g).as("%s 게이지가 등록돼 있어야 한다", name).isNotNull();
        return g.value();
    }

    @Test
    @DisplayName("기한이 지난 건이 있으면 DisputeDeadlineMissed 조건(음수)이 참이 된다")
    void firesWhenDeadlinePassed() {
        repository.save(Dispute.open("cb-missed-1", "ORD-ALERT-1", 1L, 50_000L, "미수취",
                Instant.now().minus(Duration.ofDays(2))));

        double remaining = gauge("dispute.nearest.deadline.seconds");
        double open = gauge("dispute.open.count");

        // monitoring/alert-rules.yml: dispute_nearest_deadline_seconds < 0 and dispute_open_count > 0
        assertThat(remaining).as("기한이 지났으므로 음수여야 한다").isNegative();
        assertThat(open).isPositive();
        assertThat(remaining < 0 && open > 0).as("DisputeDeadlineMissed 가 발화한다").isTrue();
    }

    @Test
    @DisplayName("기한이 넉넉하면 두 알림 다 조용하다 — 항상 울리는 알림은 알림이 아니다")
    void staysQuietWhenDeadlineIsFar() {
        repository.deleteAll();
        repository.save(Dispute.open("cb-far-1", "ORD-ALERT-2", 2L, 50_000L, "미수취",
                Instant.now().plus(Duration.ofDays(20))));

        double remaining = gauge("dispute.nearest.deadline.seconds");

        assertThat(remaining).isGreaterThan(APPROACHING_SECONDS);
        assertThat(remaining < 0).as("DisputeDeadlineMissed 는 조용해야 한다").isFalse();
        assertThat(remaining < APPROACHING_SECONDS).as("Approaching 도 조용해야 한다").isFalse();
    }
}
