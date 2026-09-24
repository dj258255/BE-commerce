package com.beomsu.becommerce.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.beomsu.becommerce.RepoRoot;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * CDC 감시(#252)의 계약. 상태가 RUNNING 이 아니면 0, 닿지 않으면 0, 하트비트는 커넥터 이름으로 나이를 낸다.
 * 알림 규칙이 찾는 {@code cdc_*} 이름이 실제로 노출되는지도 대조한다(안 울리는 알림은 잘 도는 알림과 구별되지 않는다).
 */
class CdcHealthMonitorTest {

    private static final String STATUS = """
            {"catalog-cdc": {"status": {"connector": {"state": "RUNNING"}, "tasks": [{"id": 0, "state": "RUNNING"}]},
                             "info": {"config": {"topic.prefix": "pay-cdc-catalog"}}},
             "user-activity-cdc": {"status": {"connector": {"state": "RUNNING"}, "tasks": [{"id": 0, "state": "FAILED"}]},
                             "info": {"config": {"topic.prefix": "pay-cdc"}}}}
            """;

    private final PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    private final RestClient.Builder builder = RestClient.builder().baseUrl("http://connect");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final CdcHealthMonitor monitor = new CdcHealthMonitor(builder, Duration.ZERO, registry);

    private double gauge(String name, String connector) {
        return registry.get(name).tag("connector", connector).gauge().value();
    }

    @Test
    @DisplayName("태스크 하나라도 RUNNING 이 아니면 0 이고, Connect 에 닿지 않으면 전부 0 이다")
    void runningNeedsConnectorAndAllTasks() {
        server.expect(ExpectedCount.once(), requestTo("http://connect/connectors?expand=status&expand=info"))
                .andRespond(withSuccess(STATUS, MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.once(), requestTo("http://connect/connectors?expand=status&expand=info"))
                .andRespond(withServerError());

        monitor.refresh();
        assertThat(gauge("cdc.connector.running", "catalog-cdc")).isEqualTo(1.0);
        assertThat(gauge("cdc.connector.running", "user-activity-cdc")).isEqualTo(0.0);
        assertThat(registry.get("cdc.connect.reachable").gauge().value()).isEqualTo(1.0);

        monitor.refresh();
        assertThat(gauge("cdc.connector.running", "catalog-cdc")).isEqualTo(0.0);
        assertThat(registry.get("cdc.connect.reachable").gauge().value()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("하트비트 나이는 커넥터 이름으로 나오고, 늦게 도착한 옛 하트비트는 나이를 되돌리지 않는다")
    void heartbeatAgeByConnector() {
        server.expect(requestTo("http://connect/connectors?expand=status&expand=info"))
                .andRespond(withSuccess(STATUS, MediaType.APPLICATION_JSON));
        monitor.refresh();
        assertThat(gauge("cdc.heartbeat.age", "catalog-cdc")).isLessThan(5.0);   // 받기 전에는 감시 시작부터 센다

        long now = System.currentTimeMillis();
        monitor.onHeartbeat(heartbeat("pay-cdc-catalog", now + 60_000));        // 가장 최근
        monitor.onHeartbeat(heartbeat("pay-cdc-catalog", now - 90_000));        // 순서가 뒤바뀌어 늦게 온 옛 레코드
        assertThat(gauge("cdc.heartbeat.age", "catalog-cdc")).isLessThan(-50.0); // 최근 것 기준(미래라 음수)
        assertThat(gauge("cdc.heartbeat.age", "user-activity-cdc")).isLessThan(5.0);
    }

    private static ConsumerRecord<String, String> heartbeat(String prefix, long timestamp) {
        return new ConsumerRecord<>(CdcHealthMonitor.HEARTBEAT_PREFIX + prefix, 0, 0L, timestamp, TimestampType.CREATE_TIME,
                0, 0, "k", "v", new RecordHeaders(), Optional.empty());
    }

    @Test
    @DisplayName("알림 파일의 cdc_* 이름이 전부 실제 노출 이름에 있다")
    void alertsReferenceRealMetricNames() throws Exception {
        server.expect(requestTo("http://connect/connectors?expand=status&expand=info"))
                .andRespond(withSuccess(STATUS, MediaType.APPLICATION_JSON));
        monitor.refresh();
        Set<String> exposed = Pattern.compile("^# TYPE (\\S+) ", Pattern.MULTILINE).matcher(registry.scrape()).results()
                .map(m -> m.group(1)).collect(Collectors.toCollection(TreeSet::new));

        String rules = Files.readString(RepoRoot.resolve("monitoring/alert-rules.yml"), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("\\b(cdc_[a-z0-9_]+)\\b").matcher(rules);
        Set<String> wanted = new TreeSet<>();
        while (m.find()) {
            wanted.add(m.group(1));
        }
        assertThat(wanted).containsExactlyInAnyOrder("cdc_connector_running", "cdc_heartbeat_age_seconds", "cdc_connect_reachable");
        assertThat(exposed).containsAll(wanted);
    }
}
