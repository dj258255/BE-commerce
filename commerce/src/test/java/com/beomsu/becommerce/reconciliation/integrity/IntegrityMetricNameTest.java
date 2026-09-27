package com.beomsu.becommerce.reconciliation.integrity;

import com.beomsu.becommerce.RepoRoot;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 정합성 게이지가 프로메테우스에 나가는 이름과 알림(PaymentIntegrityViolation)이 찾는 이름이 같은지(#389).
 * 안 울리는 알림은 잘 도는 알림과 화면에서 구별되지 않는다.
 */
class IntegrityMetricNameTest {

    @Test
    @DisplayName("불변식마다 integrity_violations{invariant} 로 나가고 알림이 그 이름을 찾는다")
    void gaugeNameMatchesAlert() throws Exception {
        var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        new IntegrityCheckScheduler(new IntegrityCheckService(mock(JdbcTemplate.class), Duration.ZERO, Clock.systemUTC()), registry);

        String scraped = registry.scrape();
        for (Invariant inv : Invariant.values()) {
            assertThat(scraped).contains("integrity_violations{invariant=\"" + inv.name() + "\"}");
        }
        String rules = Files.readString(RepoRoot.resolve("monitoring/alert-rules.yml"), StandardCharsets.UTF_8);
        assertThat(rules).contains("max by (invariant) (integrity_violations) > 0");
    }
}
