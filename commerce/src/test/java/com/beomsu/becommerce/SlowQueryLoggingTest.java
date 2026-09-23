package com.beomsu.becommerce;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.hibernate.cfg.JdbcSettings;
import org.hibernate.engine.jdbc.spi.SqlStatementLogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 느린 쿼리 로깅이 <b>실제로 찍히는지</b> 검증한다 (성능 리포트 13절).
 *
 * <p><b>왜 이 테스트가 있나</b>: 설정을 처음 넣을 때 프로퍼티 이름을 틀렸다.
 * {@code hibernate.session.events.log.LOG_QUERIES_SLOWER_THAN_MS}는 Hibernate <b>6.1까지</b>의
 * 이름이고, 6.2에서 {@code hibernate.log_slow_query}로 바뀌었다(이 프로젝트는 7.4).
 * 그런데 <b>옛 이름을 써도 예외도 경고도 나지 않는다.</b> 조용히 무시될 뿐이다.
 *
 * <p>즉 그대로 뒀다면 "슬로우 쿼리 로깅을 켰다"고 문서에 적힌 채 아무것도 안 찍혔을 것이다.
 * 이 프로젝트에서 반복해 잡은 <b>"만들어놓고 안 도는 것"</b>이 하나 더 늘어날 뻔했다.
 * 그래서 <b>설정이 맞다</b>가 아니라 <b>로그가 찍힌다</b>를 검증한다.
 *
 * <p>DB가 필요 없다. Hibernate의 {@link SqlStatementLogger}를 직접 호출해
 * 임계 초과 시 {@code org.hibernate.SQL_SLOW} 로거로 나가는지만 본다 —
 * 느린 쿼리를 인위적으로 만들 필요도, 컨테이너를 띄울 필요도 없다.
 */
class SlowQueryLoggingTest {

    /** Hibernate가 느린 쿼리를 내보내는 로거. {@code SqlStatementLogger}가 이 이름으로 찍는다. */
    private static final String SLOW_QUERY_LOGGER = "org.hibernate.SQL_SLOW";

    private ch.qos.logback.classic.Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(SLOW_QUERY_LOGGER);
        logger.setLevel(Level.INFO);
        appender = new ListAppender<>();
        appender.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
        appender.stop();
    }

    @Test
    @DisplayName("임계를 넘는 쿼리는 org.hibernate.SQL_SLOW로 찍힌다 — SQL 본문과 소요시간을 함께")
    void slowQueryIsLogged() {
        // 임계 1ms. 아래에서 5ms 걸린 것처럼 신고하므로 반드시 걸려야 한다.
        SqlStatementLogger statementLogger = new SqlStatementLogger(false, false, false, 1);

        long startedFiveMillisAgo = System.nanoTime() - 5_000_000L;
        statementLogger.logSlowQuery("select * from payments where order_no = ?", startedFiveMillisAgo, null);

        assertThat(appender.list)
                .as("임계(1ms)를 넘겼는데 아무것도 안 찍혔다면 로거 이름이 바뀐 것이다")
                .isNotEmpty();
        String message = appender.list.get(0).getFormattedMessage();
        // 메시지 형식: "Slow query took 5 milliseconds [select ...]"
        // 처음에 "SlowQuery"로 단언했다가 깨졌다. 그래서 문서의 grep 예시도 함께 고쳤다 —
        // 검색어를 틀리면 로그가 찍혀도 못 찾는다.
        assertThat(message).contains("Slow query took");
        assertThat(message)
                .as("소요 시간이 없으면 얼마나 느린지 알 수 없다")
                .contains("5 milliseconds");
        assertThat(message)
                .as("어떤 쿼리가 느렸는지 알 수 없으면 로그가 쓸모없다")
                .contains("select * from payments");
    }

    @Test
    @DisplayName("임계 이내면 찍지 않는다 — 정상 트래픽에서 로그가 시끄러우면 아무도 안 본다")
    void fastQueryIsNotLogged() {
        SqlStatementLogger statementLogger = new SqlStatementLogger(false, false, false, 1000);

        long startedOneMilliAgo = System.nanoTime() - 1_000_000L;
        statementLogger.logSlowQuery("select 1", startedOneMilliAgo, null);

        assertThat(appender.list).isEmpty();
    }

    @Test
    @DisplayName("임계 0이면 기능이 꺼진다 — 끄는 방법이 동작하는지도 확인한다")
    void thresholdZeroDisablesLogging() {
        SqlStatementLogger statementLogger = new SqlStatementLogger(false, false, false, 0);

        long startedLongAgo = System.nanoTime() - 999_000_000L;
        statementLogger.logSlowQuery("select * from orders", startedLongAgo, null);

        assertThat(appender.list).isEmpty();
    }

    @Test
    @DisplayName("application.yml이 쓰는 프로퍼티 이름이 이 Hibernate 버전에 실재한다")
    void propertyNameMatchesHibernateVersion() {
        // 6.1까지는 session.events.log.LOG_QUERIES_SLOWER_THAN_MS였다. 옛 이름을 쓰면
        // 조용히 무시되므로, 우리가 yml에 적은 이름이 현재 버전의 상수와 같은지 못 박는다.
        assertThat(JdbcSettings.LOG_SLOW_QUERY)
                .as("application.yml의 spring.jpa.properties.hibernate.log_slow_query와 일치해야 한다")
                .isEqualTo("hibernate.log_slow_query");
    }
}
