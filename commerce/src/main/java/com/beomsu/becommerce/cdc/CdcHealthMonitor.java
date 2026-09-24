package com.beomsu.becommerce.cdc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Debezium 커넥터가 <b>떠 있는지</b>와 <b>흐르는지</b>를 따로 본다(#252).
 *
 * <ul>
 *   <li>{@code cdc.connector.running{connector}}: Connect REST 에서 커넥터와 모든 태스크가 {@code RUNNING} 이면 1</li>
 *   <li>{@code cdc.heartbeat.age{connector}}: 그 커넥터의 하트비트 토픽({@code __debezium-heartbeat.<topic.prefix>})에서
 *       마지막 레코드가 찍힌 뒤 지난 초. 한 번도 못 받았으면 감시를 시작한 뒤 지난 초</li>
 *   <li>{@code cdc.connect.reachable}: Connect REST 에 닿으면 1</li>
 * </ul>
 *
 * <p><b>왜 둘 다 보나</b>: E1b 에서 커넥터가 {@code RUNNING} 인 채 안에서 재시작만 돌았다. 상태만 보면 정상이었다.
 * 하트비트는 커넥터가 binlog 를 실제로 읽을 때 찍힌다. DB 가 조용해도 약 48초마다 찍혔다(#252 실측).
 *
 * <p>{@code app.cdc.health.connect-url} 이 비어 있으면 뜨지 않는다. CDC 를 쓰지 않는 환경에서 "닿지 않음"을 알리지 않게 하려는 것이다.
 */
@Component
@Profile("kafka")
@ConditionalOnExpression("!'${app.cdc.health.connect-url:}'.isEmpty()")
class CdcHealthMonitor {

    private static final Logger log = LoggerFactory.getLogger(CdcHealthMonitor.class);
    static final String HEARTBEAT_PREFIX = "__debezium-heartbeat.";

    private final RestClient connect;
    private final MeterRegistry registry;
    private final ObjectMapper json = new ObjectMapper();
    private final long startedAt = System.currentTimeMillis();
    private final AtomicInteger reachable = new AtomicInteger();
    private final Map<String, AtomicInteger> running = new ConcurrentHashMap<>();
    /** topic.prefix → 마지막 하트비트 시각(ms). */
    private final Map<String, AtomicLong> lastHeartbeat = new ConcurrentHashMap<>();
    /** 커넥터 이름 → topic.prefix. 하트비트 나이를 커넥터 이름으로 내려고 둔다. */
    private final Map<String, String> prefixOf = new ConcurrentHashMap<>();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "cdc-health");
        t.setDaemon(true);
        return t;
    });

    @Autowired
    CdcHealthMonitor(@Value("${app.cdc.health.connect-url}") String connectUrl,
                     @Value("${app.cdc.health.interval:30s}") Duration interval, MeterRegistry registry) {
        this(RestClient.builder().baseUrl(connectUrl).requestFactory(timeouts()), interval, registry);
    }

    /** @param interval 0 이면 스스로 읽지 않는다(테스트가 {@link #refresh()} 를 부른다) */
    CdcHealthMonitor(RestClient.Builder connect, Duration interval, MeterRegistry registry) {
        this.connect = connect.build();
        this.registry = registry;
        Gauge.builder("cdc.connect.reachable", reachable, AtomicInteger::get).register(registry);
        if (!interval.isZero()) {
            executor.scheduleWithFixedDelay(this::refresh, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private static SimpleClientHttpRequestFactory timeouts() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2_000);
        factory.setReadTimeout(5_000);
        return factory;
    }

    /** Connect REST 에서 커넥터 상태와 topic.prefix 를 읽는다. 닿지 않으면 모든 커넥터를 0 으로 둔다. */
    void refresh() {
        try {
            String body = connect.get().uri("/connectors?expand=status&expand=info").retrieve().body(String.class);
            JsonNode all = json.readTree(body == null ? "{}" : body);
            reachable.set(1);
            for (Iterator<Map.Entry<String, JsonNode>> it = all.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                String name = e.getKey();
                running(name).set(isRunning(e.getValue().path("status")) ? 1 : 0);
                String prefix = e.getValue().path("info").path("config").path("topic.prefix").asText("");
                if (!prefix.isEmpty() && prefixOf.putIfAbsent(name, prefix) == null) {
                    AtomicLong last = lastHeartbeat.computeIfAbsent(prefix, p -> new AtomicLong(startedAt));
                    Gauge.builder("cdc.heartbeat.age", last, l -> (System.currentTimeMillis() - l.get()) / 1000.0)
                            .tag("connector", name).baseUnit("seconds").register(registry);
                }
            }
        } catch (Exception e) {
            reachable.set(0);
            running.values().forEach(v -> v.set(0));
            log.warn("Connect REST 에 닿지 않는다: {}", e.toString());
        }
    }

    static boolean isRunning(JsonNode status) {
        if (!"RUNNING".equals(status.path("connector").path("state").asText())) {
            return false;
        }
        JsonNode tasks = status.path("tasks");
        if (!tasks.isArray() || tasks.isEmpty()) {
            return false;
        }
        for (JsonNode t : tasks) {
            if (!"RUNNING".equals(t.path("state").asText())) {
                return false;
            }
        }
        return true;
    }

    private AtomicInteger running(String name) {
        return running.computeIfAbsent(name, n -> {
            AtomicInteger v = new AtomicInteger();
            Gauge.builder("cdc.connector.running", v, AtomicInteger::get).tag("connector", n).register(registry);
            return v;
        });
    }

    /** 하트비트는 그 순간부터만 본다(부팅마다 새 그룹, latest). 과거 하트비트는 흐른다는 증거가 아니다. */
    @KafkaListener(topicPattern = "__debezium-heartbeat\\..*", groupId = "cdc-health-${random.uuid}",
            properties = {"auto.offset.reset=latest", "metadata.max.age.ms=30000"})
    void onHeartbeat(ConsumerRecord<String, String> record) {
        String prefix = record.topic().substring(HEARTBEAT_PREFIX.length());
        lastHeartbeat.computeIfAbsent(prefix, p -> new AtomicLong()).accumulateAndGet(record.timestamp(), Math::max);
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }
}
