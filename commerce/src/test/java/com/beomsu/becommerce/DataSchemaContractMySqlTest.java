package com.beomsu.becommerce;

import com.beomsu.becommerce.testsupport.SharedContainers;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 데이터 영역(data/)이 쓰는 원본 스키마 사본이 결제 쪽 실제 마이그레이션과 맞는지 대조한다.
 *
 * <p>data/ 의 실험은 {@code data/compose/init/mysql/01_schema.sql} 로 원본 MySQL 을 만든다. 그 파일은 Flyway 마이그레이션에서
 * 데이터 쪽이 읽는 표만 골라 합친 사본이라, 마이그레이션이 그 표를 바꾸면 사본이 모르는 사이에 어긋난다.
 * 이 테스트는 두 스키마를 같은 MySQL 에 만들어 열 이름 · 타입 · NULL 허용을 비교한다.
 *
 * <ul>
 *   <li>사본에 있는 열은 원본에 같은 타입 · 같은 NULL 허용으로 있어야 한다</li>
 *   <li>원본에만 있는 열은 {@link #OMITTED} 에 적힌 것이어야 한다(뺀 이유는 data/docs/source-schema.md)</li>
 * </ul>
 * 마이그레이션이 이 표들에 열을 더하면 여기서 실패한다. 사본에 넣거나 OMITTED 와 source-schema.md 에 이유를 적는다.
 */
@Tag("integration")
class DataSchemaContractMySqlTest {

    // 깊이를 박지 않고 저장소 루트를 찾아 올라간다(ADR-049).
    private static final Path COPY = RepoRoot.resolve("data/compose/init/mysql/01_schema.sql");

    /** 원본에는 있지만 데이터 쪽 사본에서 일부러 뺀 열. 이유는 data/docs/source-schema.md "뺀 열". */
    private static final Map<String, Set<String>> OMITTED = Map.of(
            "sellers", Set.of("representative_name", "payout_account", "representative_birth_date"),
            "products", Set.of("description", "image_url", "featured",
                    "subcategory_code", "product_type", "colour_code", "colour_name"),
            "payments", Set.of("card_fingerprint", "recovery_attempts", "recovery_next_at"),
            "settlements", Set.of("payout_hold_reason", "payout_instruction_reference", "payout_reconciled_at")
    );

    /** 접두어로 뺀 열(같은 묶음의 열이 여럿이라 이름을 다 적지 않는다). */
    private static final Map<String, List<String>> OMITTED_PREFIXES = Map.of(
            "settlements", List.of("payout_reconciliation_", "payout_report_")
    );

    @Test
    @DisplayName("data/ 의 원본 스키마 사본이 Flyway 마이그레이션과 열 단위로 맞는다")
    void copyMatchesMigrations() throws Exception {
        String source = SharedContainers.freshDatabase("contract_src");
        Flyway.configure().dataSource(source, SharedContainers.username(), SharedContainers.password())
                .locations("classpath:db/migration").load().migrate();

        String copy = SharedContainers.freshDatabase("contract_copy");
        try (Connection c = connect(copy); Statement s = c.createStatement()) {
            for (String sql : statements(Files.readString(COPY, StandardCharsets.UTF_8))) {
                s.execute(sql);
            }
        }

        Map<String, Map<String, String>> copyCols = columns(copy);
        Map<String, Map<String, String>> sourceCols = columns(source);
        assertThat(copyCols).as("사본에서 표를 하나도 못 읽음 — 파일 경로나 분할을 확인").isNotEmpty();

        List<String> problems = new ArrayList<>();
        for (var table : copyCols.entrySet()) {
            Map<String, String> src = sourceCols.get(table.getKey());
            if (src == null) {
                problems.add(table.getKey() + ": 원본 마이그레이션에 없는 표");
                continue;
            }
            for (var col : table.getValue().entrySet()) {
                String expected = src.get(col.getKey());
                if (expected == null) {
                    problems.add(table.getKey() + "." + col.getKey() + ": 원본에 없는 열");
                } else if (!expected.equals(col.getValue())) {
                    problems.add(table.getKey() + "." + col.getKey() + ": 원본 " + expected + " / 사본 " + col.getValue());
                }
            }
            for (String col : src.keySet()) {
                if (!table.getValue().containsKey(col) && !omitted(table.getKey(), col)) {
                    problems.add(table.getKey() + "." + col + ": 원본에만 있음 — 사본에 넣거나 뺀 이유를 적을 것");
                }
            }
        }

        System.out.printf("%n=== data/ 원본 스키마 사본 대조: 표 %d개 ===%n", copyCols.size());
        problems.forEach(p -> System.out.println("  " + p));
        assertThat(problems).as("data/compose/init/mysql/01_schema.sql 이 마이그레이션과 어긋남").isEmpty();
    }

    private static boolean omitted(String table, String column) {
        if (OMITTED.getOrDefault(table, Set.of()).contains(column)) {
            return true;
        }
        return OMITTED_PREFIXES.getOrDefault(table, List.of()).stream().anyMatch(column::startsWith);
    }

    /** 표 → (열 → "타입 NULL허용"). */
    private static Map<String, Map<String, String>> columns(String url) throws SQLException {
        Map<String, Map<String, String>> result = new TreeMap<>();
        try (Connection c = connect(url); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE "
                     + "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()")) {
            while (rs.next()) {
                result.computeIfAbsent(rs.getString(1), k -> new TreeMap<>())
                        .put(rs.getString(2), rs.getString(3) + " " + ("YES".equals(rs.getString(4)) ? "NULL" : "NOT NULL"));
            }
        }
        return result;
    }

    /** 주석 줄을 지우고 세미콜론으로 나눠 CREATE TABLE 만 남긴다(USE · 시드 INSERT · CDC 계정 생성은 대조 대상이 아님). */
    private static List<String> statements(String script) {
        String body = script.lines()
                .filter(l -> !l.stripLeading().startsWith("--"))
                .collect(Collectors.joining("\n"));
        List<String> result = new ArrayList<>();
        for (String part : body.split(";")) {
            String sql = part.strip();
            if (sql.toUpperCase().startsWith("CREATE TABLE")) {
                result.add(sql);
            }
        }
        return result;
    }

    private static Connection connect(String url) throws SQLException {
        return DriverManager.getConnection(url, SharedContainers.username(), SharedContainers.password());
    }
}
