package com.beomsu.becommerce.testsupport;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 통합 테스트가 나눠 쓰는 MySQL · Redis(#276). <b>JVM 에서 한 번만 띄운다.</b>
 *
 * <p>예전에는 테스트 클래스마다 {@code @Container static} 으로 새 컨테이너를 띄웠다. CI 한 번에 MySQL 이 25번 떴고
 * 기동만 약 520초였다. 컨테이너는 나눠 쓰되 격리는 지금과 같게 둔다.
 * <ul>
 *   <li>MySQL — 테스트 클래스마다 <b>새 데이터베이스</b>를 준다. 스프링 테스트는 마이그레이션된 템플릿의 복사본을 받는다
 *       ({@link #migratedDatabase}, #280) — 클래스마다 Flyway 가 68개를 처음부터 돌던 것(번마다 약 10초)을 없앴다.
 *       마이그레이션 자체를 보는 테스트는 빈 DB 를 받는다({@link #freshDatabase})</li>
 *   <li>Redis — 클래스가 시작할 때 비운다({@link #flushRedis})</li>
 * </ul>
 *
 * <p>{@code @Testcontainers} · {@code @Container} 와 섞지 않는다. 그 확장은 클래스가 끝나면 컨테이너를 멈춘다.
 * 여기 컨테이너는 JVM 이 끝날 때 멈춘다. Ryuk 에만 맡기지 않는다 — 로컬 Docker Desktop 에서 Ryuk 이 뜨지 못해
 * 실행마다 MySQL · Redis 가 남았다(종료 훅이 대신 치운다). 테스트는 한 JVM 안에서 차례로 돈다고 가정한다
 * (Gradle 이 여러 JVM 으로 나눠 돌리면 JVM 마다 따로 띄운다).
 */
public final class SharedContainers {

    private static final String USER = "becommerce";
    private static final String PASSWORD = "becommerce";
    private static final AtomicInteger SEQ = new AtomicInteger();

    private SharedContainers() {
    }

    private static final class MySqlHolder {
        // 원시 JDBC 테스트들이 각자 주던 옵션을 합쳤다(잠금 비교의 max-connections, 인덱스 실측의 버퍼 풀)
        static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
                .withDatabaseName("becommerce")
                .withUsername(USER)
                .withPassword(PASSWORD)
                .withCommand("--max-connections=400", "--innodb-buffer-pool-size=536870912");

        static {
            MYSQL.start();
            Runtime.getRuntime().addShutdownHook(new Thread(MYSQL::stop, "shared-mysql-stop"));
        }
    }

    /** 마이그레이션을 한 번만 돈 템플릿. 스프링 테스트의 새 DB 는 이것을 복사한다. */
    private static final class TemplateHolder {
        // 상수로 두면 컴파일러가 값을 박아 넣어 이 클래스의 초기화(템플릿 생성)가 돌지 않는다 — 실제로 그렇게 빈 DB 를 복사했다
        private static final String NAME = String.valueOf("migrated_template");

        static String name() {
            return NAME;
        }

        static {
            MySQLContainer<?> mysql = mysql();
            String root = rootUrl(mysql);
            try (Connection c = DriverManager.getConnection(root, "root", PASSWORD); Statement s = c.createStatement()) {
                s.execute("CREATE DATABASE `" + NAME + "`");
            } catch (SQLException e) {
                throw new IllegalStateException("템플릿 DB 를 만들지 못했다", e);
            }
            // 앱과 같은 설정(spring.flyway: 기본 위치 · baseline-on-migrate)
            Flyway.configure().dataSource(root + NAME, "root", PASSWORD)
                    .locations("classpath:db/migration").baselineOnMigrate(true).load().migrate();
        }
    }

    private static final class RedisHolder {
        static final GenericContainer<?> REDIS =
                new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

        static {
            REDIS.start();
            Runtime.getRuntime().addShutdownHook(new Thread(REDIS::stop, "shared-redis-stop"));
        }
    }

    public static MySQLContainer<?> mysql() {
        return MySqlHolder.MYSQL;
    }

    public static GenericContainer<?> redis() {
        return RedisHolder.REDIS;
    }

    /**
     * 빈 데이터베이스를 새로 만들고 그 JDBC URL 을 준다. 이름은 {@code prefix} 에 순번을 붙인다.
     * 테스트 사용자(becommerce)에게 그 DB 의 모든 권한을 준다.
     */
    public static String freshDatabase(String prefix) {
        String name = newName(prefix);
        createDatabase(name);
        return rootUrl(mysql()) + name;
    }

    /**
     * 마이그레이션이 끝난 새 데이터베이스(#280). 템플릿을 컨테이너 안에서 {@code mysqldump | mysql} 로 복사한다 —
     * 외래 키 · AUTO_INCREMENT · 시드 데이터 · {@code flyway_schema_history} 가 그대로 옮겨져 앱의 Flyway 는 검증만 한다.
     */
    public static String migratedDatabase(String prefix) {
        String template = TemplateHolder.name();
        String name = newName(prefix);
        createDatabase(name);
        String auth = "-uroot -p" + PASSWORD;
        // pipefail: 덤프가 실패해도 뒤의 mysql 이 성공하면 빈 DB 가 조용히 만들어진다
        exec("set -o pipefail; mysqldump " + auth + " --no-tablespaces --single-transaction --skip-comments " + template
                + " | mysql " + auth + " " + name, "템플릿을 복사하지 못했다: " + name);
        return rootUrl(mysql()) + name;
    }

    private static String newName(String prefix) {
        return (prefix.replaceAll("[^A-Za-z0-9]", "_") + "_" + SEQ.incrementAndGet()).toLowerCase(Locale.ROOT);
    }

    private static String rootUrl(MySQLContainer<?> mysql) {
        return "jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306) + "/";
    }

    private static void createDatabase(String name) {
        // MySQLContainer 는 사용자가 root 가 아니면 root 비밀번호를 같은 값으로 둔다
        try (Connection c = DriverManager.getConnection(rootUrl(mysql()), "root", PASSWORD); Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE `" + name + "`");
            s.execute("GRANT ALL PRIVILEGES ON `" + name + "`.* TO '" + USER + "'@'%'");
        } catch (SQLException e) {
            throw new IllegalStateException("테스트 DB 를 만들지 못했다: " + name, e);
        }
    }

    private static void exec(String shell, String failure) {
        try {
            var result = mysql().execInContainer("sh", "-c", shell);
            if (result.getExitCode() != 0) {
                throw new IllegalStateException(failure + " — " + result.getStderr());
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException(failure, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure, e);
        }
    }

    /** 클래스 시작 때 부른다. 예전에는 클래스마다 새 Redis 컨테이너였으니 빈 상태로 시작하는 것이 같은 조건이다. */
    public static void flushRedis() {
        try {
            var result = redis().execInContainer("redis-cli", "FLUSHALL");
            if (result.getExitCode() != 0) {
                throw new IllegalStateException("Redis 를 비우지 못했다: " + result.getStderr());
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Redis 를 비우지 못했다", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Redis 를 비우다 인터럽트됐다", e);
        }
    }

    /**
     * 스프링 통합 테스트의 공통 속성: 마이그레이션된 새 DB · 빈 Redis · Kafka 끔. 클래스의 {@code @DynamicPropertySource} 에서 부른다.
     * DB 는 여기서 한 번 만든다 — URL 공급자 안에서 만들면 부를 때마다 새 DB 가 생긴다.
     */
    public static void register(DynamicPropertyRegistry registry, String prefix) {
        String url = migratedDatabase(prefix) + "?serverTimezone=UTC&characterEncoding=UTF-8";
        flushRedis();
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> USER);
        registry.add("spring.datasource.password", () -> PASSWORD);
        registry.add("spring.data.redis.host", () -> redis().getHost());
        registry.add("spring.data.redis.port", () -> redis().getMappedPort(6379).toString());
        registry.add("spring.kafka.bootstrap-servers", () -> "");
    }

    public static String username() {
        return USER;
    }

    public static String password() {
        return PASSWORD;
    }
}
