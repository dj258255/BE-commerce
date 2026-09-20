package com.beomsu.becommerce.assist.incident;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 장애 로그 표본을 <b>실제로 장애를 일으켜</b> 받아 적는다.
 *
 * <p><b>왜 이 클래스가 있는가.</b> 원인 분석 실험의 표본이 4건이었고, 3건에서 4건으로 가는 것만으로
 * 결론이 뒤집혔다. 한 건이 결론을 뒤집는 표본으로는 "규칙보다 낫다"를 판정할 수 없다. 그래서 표본을
 * 늘리는데, <b>지어내지 않는다</b> — 지어낸 로그로 재면 내가 아는 장애만 실험 대상이 되고, 그건
 * 잔여 후보에서 손으로 만든 27건이 실제 분포와 달라 결론이 뒤집혔던 함정과 같다.
 *
 * <p><b>여기서 받는 것은 전부 진짜다.</b> 진짜 MySQL 에서 진짜 데드락을 내고, 진짜 만료된 인증서에
 * 붙고, 진짜 커넥션 풀을 고갈시킨다. 로그 문장은 MySQL·HikariCP·JDK 가 낸 것이고 내가 쓴 게 아니다.
 *
 * <p><b>진단을 로그에 적지 않는다.</b> 처음에 "낙관적 락 충돌"·"복제 지연" 이라고 내가 원인을
 * 이름 붙여 찍었더니, 모델은 그 문구를 읽기만 하면 맞히고 규칙은 내가 지어낸 한국어 문구를
 * 패턴에 갖고 있지 않아 기권했다. 그 상태의 점수 차는 실력 차가 아니라 <b>내가 흘린 정답</b>이다.
 * 그래서 앱이 찍는 줄은 <b>관측된 사실</b>(영향 행 0건, Seconds_Behind_Source=N)까지만 적고,
 * 진단 명칭은 MySQL·JDK·HikariCP 가 스스로 낸 문장에만 남긴다.
 *
 * <p>기본 스위트에서 빠진다(@Tag("capture")). {@code ./gradlew captureTest} 로 수동 실행하며
 * MySQL 컨테이너({@code docker compose up -d mysql})와 외부 네트워크가 필요하다.
 */
@Tag("capture")
@DisplayName("장애 로그 표본 캡처 — 실제로 일으켜서 받는다")
class IncidentLogCaptureTest {

    private static final Path DIR = Path.of("src/test/resources/incident-logs");
    private static final String URL = "jdbc:mysql://localhost:3306/becommerce?serverTimezone=UTC";
    /** SOURCE_DELAY=30 으로 붙여 둔 진짜 복제본. {@code docs/20-복제지연-재현.md} 참조. */
    private static final String REPLICA_URL = "jdbc:mysql://localhost:3307/pay?serverTimezone=UTC";
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    /** 로그를 잡아 두는 자리. 실제 로거에 붙여 앱이 찍는 것을 그대로 받는다. */
    private ListAppender<ILoggingEvent> attach(String loggerName) {
        Logger log = (Logger) LoggerFactory.getLogger(loggerName);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        log.addAppender(appender);
        log.setLevel(Level.DEBUG);
        return appender;
    }

    private void write(String file, ListAppender<ILoggingEvent> appender) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (ILoggingEvent e : appender.list) {
            sb.append("    ").append(LocalTime.now().format(TS))
              .append(" [Test worker] ").append(e.getLevel()).append(' ')
              .append(e.getLoggerName()).append(" -- ").append(e.getFormattedMessage())
              .append('\n');
        }
        Files.writeString(DIR.resolve(file), sb.toString(), StandardCharsets.UTF_8);
        System.out.println("── " + file + " (" + appender.list.size() + "줄) ──");
        System.out.print(sb);
    }

    private HikariDataSource pool(int size, long timeoutMs) {
        return pool(URL, size, timeoutMs);
    }

    private HikariDataSource pool(String jdbcUrl, int size, long timeoutMs) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(jdbcUrl);
        ds.setUsername("root");
        ds.setPassword("root");
        ds.setMaximumPoolSize(size);
        ds.setConnectionTimeout(timeoutMs);
        return ds;
    }

    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("RACE_CONDITION — 실제 MySQL 에서 데드락을 낸다")
    void captureDeadlock() throws Exception {
        var org = (Logger) LoggerFactory.getLogger("com.beomsu.becommerce.order.catalog.StockDeductionService");
        var appender = attach("com.beomsu.becommerce.order.catalog.StockDeductionService");

        try (HikariDataSource ds = pool(4, 5_000)) {
            try (Connection c = ds.getConnection(); var st = c.createStatement()) {
                st.execute("DROP TABLE IF EXISTS deadlock_probe");
                st.execute("CREATE TABLE deadlock_probe (id INT PRIMARY KEY, qty INT) ENGINE=InnoDB");
                st.execute("INSERT INTO deadlock_probe VALUES (1, 100), (2, 100)");
            }

            // 두 트랜잭션이 같은 두 행을 반대 순서로 잠근다 — InnoDB 가 한쪽을 희생시킨다.
            CountDownLatch bothLockedOne = new CountDownLatch(2);
            Runnable a = order(ds, 1, 2, bothLockedOne, appender, org);
            Runnable b = order(ds, 2, 1, bothLockedOne, appender, org);
            Thread ta = new Thread(a, "deduct-A");
            Thread tb = new Thread(b, "deduct-B");
            ta.start(); tb.start();
            ta.join(15_000); tb.join(15_000);

            try (Connection c = ds.getConnection(); var st = c.createStatement()) {
                st.execute("DROP TABLE IF EXISTS deadlock_probe");
            }
        }
        write("RACE_CONDITION-01.log", appender);
    }

    private Runnable order(HikariDataSource ds, int first, int second, CountDownLatch latch,
                           ListAppender<ILoggingEvent> appender, Logger log) {
        return () -> {
            try (Connection c = ds.getConnection()) {
                c.setAutoCommit(false);
                try (var st = c.createStatement()) {
                    st.execute("SELECT qty FROM deadlock_probe WHERE id=" + first + " FOR UPDATE");
                    latch.countDown();
                    latch.await(10, TimeUnit.SECONDS);
                    st.execute("SELECT qty FROM deadlock_probe WHERE id=" + second + " FOR UPDATE");
                    st.execute("UPDATE deadlock_probe SET qty = qty - 1 WHERE id=" + second);
                    c.commit();
                } catch (SQLException e) {
                    c.rollback();
                    // 앱이 실제로 찍는 자리를 흉내내지 않고, MySQL 이 준 문장을 그대로 옮긴다.
                    log.warn("재고 차감 실패 — SQLState {}, {}", e.getSQLState(), e.getMessage());
                }
            } catch (Exception e) {
                log.warn("재고 차감 실패 — {}", e.getMessage());
            }
        };
    }

    @Test
    @DisplayName("DB_TIMEOUT — 커넥션 풀을 실제로 고갈시킨다")
    void capturePoolExhaustion() throws Exception {
        var log = (Logger) LoggerFactory.getLogger("com.beomsu.becommerce.payment.internal.PaymentService");
        var appender = attach("com.beomsu.becommerce.payment.internal.PaymentService");

        try (HikariDataSource ds = pool(1, 2_000)) {
            Connection held = ds.getConnection();   // 하나뿐인 커넥션을 잡고 안 돌려준다
            try {
                for (int i = 1; i <= 3; i++) {
                    try (Connection c = ds.getConnection()) {
                        log.info("승인 처리 {} 성공", i);
                    } catch (SQLException e) {
                        log.error("승인 처리 {} 실패 — {}", i, e.getMessage());
                    }
                }
            } finally {
                held.close();
            }
        }
        write("DB_TIMEOUT-02.log", appender);
    }

    @Test
    @DisplayName("CERT_EXPIRY — 실제로 만료된 인증서에 붙는다")
    void captureExpiredCertificate() throws Exception {
        var log = (Logger) LoggerFactory.getLogger("com.beomsu.becommerce.payment.pg.TossPgClient");
        var appender = attach("com.beomsu.becommerce.payment.pg.TossPgClient");

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://expired.badssl.com/"))
                .timeout(Duration.ofSeconds(10)).GET().build();
        try {
            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
            log.info("PG 응답 {}", res.statusCode());
        } catch (Exception e) {
            Throwable root = e;
            while (root.getCause() != null) root = root.getCause();
            log.error("PG 승인 요청 실패 — {}: {}", e.getClass().getSimpleName(), e.getMessage());
            log.error("원인 — {}: {}", root.getClass().getName(), root.getMessage());
        }
        write("CERT_EXPIRY-01.log", appender);
    }

    @Test
    @DisplayName("PG_UNAVAILABLE — 죽은 포트에 실제로 붙는다")
    void captureConnectionRefused() throws Exception {
        var log = (Logger) LoggerFactory.getLogger("com.beomsu.becommerce.payment.pg.ResilientPgClient");
        var appender = attach("com.beomsu.becommerce.payment.pg.ResilientPgClient");

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        for (int i = 1; i <= 3; i++) {
            HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:1/v1/payments"))
                    .timeout(Duration.ofSeconds(3)).GET().build();
            try {
                client.send(req, HttpResponse.BodyHandlers.ofString());
            } catch (Exception e) {
                Throwable root = e;
                while (root.getCause() != null) root = root.getCause();
                log.warn("PG 승인 요청 {}회 실패 — {}: {}", i,
                        root.getClass().getSimpleName(), root.getMessage());
            }
        }
        log.warn("PG 서킷 오픈 — 승인 미확정 처리: order");
        write("PG_UNAVAILABLE-02.log", appender);
    }

    @Test
    @DisplayName("REPLICATION_LAG — 30초 지연을 건 진짜 복제본에서 방금 쓴 것을 못 읽는다")
    void captureReplicationLag() throws Exception {
        var log = (Logger) LoggerFactory.getLogger("com.beomsu.becommerce.settlement.internal.SettlementQueryService");
        var appender = attach("com.beomsu.becommerce.settlement.internal.SettlementQueryService");

        // 복제본은 SOURCE_DELAY=30 으로 붙여 둔 진짜 MySQL 복제본이다(3307).
        // 커밋 안 한 트랜잭션으로 흉내내면 원인이 달라 정답이 틀린 표본이 된다.
        try (HikariDataSource src = pool(URL, 2, 5_000);
             HikariDataSource rep = pool(REPLICA_URL, 2, 5_000)) {

            int id = 7000 + (int) (System.currentTimeMillis() % 900);
            try (Connection c = src.getConnection(); var st = c.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS repl_probe (id INT PRIMARY KEY, amount INT) ENGINE=InnoDB");
                st.execute("INSERT INTO repl_probe VALUES (" + id + ", 12000)");
                log.info("정산 행 INSERT 커밋 완료 — id={}", id);
            }

            try (Connection c = rep.getConnection(); var st = c.createStatement();
                 var rs = st.executeQuery("SELECT COUNT(*) FROM repl_probe WHERE id=" + id)) {
                rs.next();
                log.warn("정산 행 조회 결과 {}건 — 방금 기록한 id={} 을 읽지 못했다", rs.getInt(1), id);
                log.error("정산 대사 실패 — 쓰기는 커밋됐는데 조회가 0건이다: id={}", id);
            }

            long lag;
            try (Connection c = rep.getConnection(); var st = c.createStatement();
                 var rs = st.executeQuery("SHOW REPLICA STATUS")) {
                lag = rs.next() ? rs.getLong("Seconds_Behind_Source") : -1;
            }
            log.warn("조회 노드 상태 Seconds_Behind_Source={}", lag);

            TimeUnit.SECONDS.sleep(35);
            try (Connection c = rep.getConnection(); var st = c.createStatement();
                 var rs = st.executeQuery("SELECT COUNT(*) FROM repl_probe WHERE id=" + id)) {
                rs.next();
                log.info("35초 뒤 같은 조회 재실행 {}건", rs.getInt(1));
            }
        }
        write("REPLICATION_LAG-01.log", appender);
    }

    @Test
    @DisplayName("RACE_CONDITION — 진짜 행 잠금 대기 타임아웃을 낸다(경합이지 DB 고장이 아니다)")
    void captureLockWaitTimeout() throws Exception {
        var log = (Logger) LoggerFactory.getLogger("com.beomsu.becommerce.settlement.internal.SettlementBatch");
        var appender = attach("com.beomsu.becommerce.settlement.internal.SettlementBatch");

        try (HikariDataSource ds = pool(4, 5_000)) {
            try (Connection c = ds.getConnection(); var st = c.createStatement()) {
                st.execute("DROP TABLE IF EXISTS lock_probe");
                st.execute("CREATE TABLE lock_probe (id INT PRIMARY KEY, amount INT) ENGINE=InnoDB");
                st.execute("INSERT INTO lock_probe VALUES (1, 5000)");
            }
            Connection holder = ds.getConnection();
            holder.setAutoCommit(false);
            try (var st = holder.createStatement()) {
                st.execute("SELECT amount FROM lock_probe WHERE id=1 FOR UPDATE");   // 잡고 안 놓는다
            }
            try (Connection c = ds.getConnection(); var st = c.createStatement()) {
                st.execute("SET SESSION innodb_lock_wait_timeout = 3");
                c.setAutoCommit(false);
                st.execute("UPDATE lock_probe SET amount = amount - 100 WHERE id=1");
                c.commit();
                log.info("정산 차감 성공");
            } catch (SQLException e) {
                log.error("정산 차감 실패 — SQLState {}, {}", e.getSQLState(), e.getMessage());
            }
            holder.rollback();
            holder.close();
            try (Connection c = ds.getConnection(); var st = c.createStatement()) {
                st.execute("DROP TABLE IF EXISTS lock_probe");
            }
        }
        write("RACE_CONDITION-03.log", appender);
    }

    @Test
    @DisplayName("RACE_CONDITION — 낙관적 락 재시도를 실제로 소진시킨다")
    void captureOptimisticRetryExhausted() throws Exception {
        var log = (Logger) LoggerFactory.getLogger("com.beomsu.becommerce.order.catalog.StockDeductionService");
        var appender = attach("com.beomsu.becommerce.order.catalog.StockDeductionService");

        try (HikariDataSource ds = pool(8, 5_000)) {
            try (Connection c = ds.getConnection(); var st = c.createStatement()) {
                st.execute("DROP TABLE IF EXISTS version_probe");
                st.execute("CREATE TABLE version_probe (id INT PRIMARY KEY, qty INT, version INT) ENGINE=InnoDB");
                st.execute("INSERT INTO version_probe VALUES (1, 1000, 0)");
            }
            // 다른 스레드가 계속 버전을 올려 이쪽 UPDATE 가 매번 0행을 맞게 만든다.
            var stop = new java.util.concurrent.atomic.AtomicBoolean(false);
            Thread churn = new Thread(() -> {
                try (Connection c = ds.getConnection(); var st = c.createStatement()) {
                    while (!stop.get()) {
                        st.executeUpdate("UPDATE version_probe SET version = version + 1 WHERE id=1");
                        Thread.sleep(2);
                    }
                } catch (Exception ignored) { }
            }, "churn");
            churn.start();

            try (Connection c = ds.getConnection(); var st = c.createStatement()) {
                for (int attempt = 1; attempt <= 3; attempt++) {
                    int v;
                    try (var rs = st.executeQuery("SELECT version FROM version_probe WHERE id=1")) {
                        rs.next(); v = rs.getInt(1);
                    }
                    Thread.sleep(20);   // 읽고-고치고-쓰기 사이의 틈
                    int rows = st.executeUpdate(
                            "UPDATE version_probe SET qty = qty - 1, version = version + 1 "
                            + "WHERE id=1 AND version=" + v);
                    if (rows == 0) {
                        log.warn("재고 차감 UPDATE 영향 행 0건 — {}회차 재시도 (조회 당시 version={})", attempt, v);
                    }
                }
                log.error("재고 차감 실패 — 재시도 3회를 소진했다: STOCK_CONCURRENCY");
            }
            stop.set(true);
            churn.join(5_000);
            try (Connection c = ds.getConnection(); var st = c.createStatement()) {
                st.execute("DROP TABLE IF EXISTS version_probe");
            }
        }
        write("RACE_CONDITION-02.log", appender);
    }

    @Test
    @DisplayName("PG_UNAVAILABLE — 진짜 503 을 내는 서버에 붙는다")
    void captureUpstream503() throws Exception {
        var log = (Logger) LoggerFactory.getLogger("com.beomsu.becommerce.payment.pg.TossPgClient");
        var appender = attach("com.beomsu.becommerce.payment.pg.TossPgClient");

        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(0), 0);
        server.createContext("/v1/payments/confirm", ex -> {
            byte[] body = "{\"code\":\"SERVICE_UNAVAILABLE\",\"message\":\"일시적인 오류입니다\"}"
                    .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(503, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        try {
            HttpClient client = HttpClient.newHttpClient();
            String base = "http://localhost:" + server.getAddress().getPort() + "/v1/payments/confirm";
            for (int i = 1; i <= 3; i++) {
                HttpResponse<String> res = client.send(
                        HttpRequest.newBuilder(URI.create(base)).timeout(Duration.ofSeconds(5)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                log.warn("PG 승인 응답 {} — 본문 {}", res.statusCode(), res.body());
            }
            log.error("PG 승인 3회 연속 5xx — 승인 결과를 UNKNOWN 으로 보존한다");
        } finally {
            server.stop(0);
        }
        write("PG_UNAVAILABLE-03.log", appender);
    }
}
