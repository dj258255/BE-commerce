package com.beomsu.becommerce;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ERD 문서가 실제 스키마와 어긋나는 것을 막는다.
 *
 * <p>이 프로젝트에서 <b>설계 문서가 구현과 갈라진 것을 두 번</b> 발견했다. {@code outbox_events}는
 * 직접 만드는 설계로 적혀 있었지만 실제로는 Modulith의 {@code event_publication}을 쓰고 있었고,
 * {@code ledger_accounts}·{@code payment_cancels}·{@code pg_transactions}는 아예 만들지 않았으며
 * {@code settlement_details}는 이름이 {@code settlement_items}로 바뀌어 있었다.
 *
 * <p>이런 어긋남은 <b>조용하다.</b> 코드는 잘 돌고 테스트도 통과한다. 문서만 틀린다. 그리고 그
 * 문서를 믿고 쿼리를 짜거나 설계를 설명하는 사람이 틀리게 된다 — 발견되는 시점이 대개
 * "왜 그 테이블이 없죠?"라는 질문일 때다.
 *
 * <p>그래서 규칙을 검사한다. ERD 문서의 모든 {@code CREATE TABLE}은 둘 중 하나여야 한다.
 * <ul>
 *   <li>마이그레이션이 실제로 만드는 테이블이거나</li>
 *   <li>같은 줄에 {@code (미구현)} 또는 {@code 실제 이름:}으로 <b>명시적으로 표시</b>되어 있거나</li>
 * </ul>
 *
 * <p>즉 "안 만든 설계를 문서에 남기는 것"은 허용한다 — 왜 그렇게 안 했는지가 기록으로 남는 건
 * 가치가 있다. 다만 <b>표시 없이</b> 남기는 것은 막는다. 표시가 없으면 읽는 사람이 그게 실물인 줄 안다.
 */
class ErdDocMatchesSchemaTest {

    private static final Path ERD = RepoRoot.resolve("docs/09-ERD-설계.md");
    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");

    /** 실물이 아님을 밝히는 표시. 이 중 하나가 같은 줄에 있으면 통과시킨다. */
    private static final List<String> NOT_REAL_MARKERS = List.of("(미구현)", "실제 이름:");

    private static final Pattern CREATE_TABLE =
            Pattern.compile("CREATE TABLE\\s+(?:IF NOT EXISTS\\s+)?`?(\\w+)`?", Pattern.CASE_INSENSITIVE);

    @Test
    @DisplayName("ERD 문서의 모든 CREATE TABLE은 실물이거나, 실물이 아님이 표시되어 있다")
    void everyDocumentedTableExistsOrIsMarked() throws IOException {
        Set<String> realTables = tablesCreatedByMigrations();
        assertThat(realTables)
                .as("마이그레이션에서 테이블을 못 찾았다면 이 검사는 아무것도 검증하지 못한다")
                .hasSizeGreaterThanOrEqualTo(30);

        List<String> unmarked = new ArrayList<>();
        for (String line : Files.readAllLines(ERD)) {
            Matcher m = CREATE_TABLE.matcher(line);
            if (!m.find()) continue;
            String table = m.group(1);
            boolean marked = NOT_REAL_MARKERS.stream().anyMatch(line::contains);
            if (!realTables.contains(table) && !marked) {
                unmarked.add(table);
            }
        }

        assertThat(unmarked)
                .as("ERD 문서에 실물 없는 테이블이 표시 없이 적혀 있다. 실제 스키마를 반영하거나, "
                    + "그 줄에 '(미구현)' 또는 '실제 이름: xxx'를 붙여 의도를 밝혀라")
                .isEmpty();
    }

    /** Flyway 마이그레이션이 실제로 만드는 테이블 이름. */
    private static Set<String> tablesCreatedByMigrations() throws IOException {
        Set<String> tables = new LinkedHashSet<>();
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            for (Path f : files.filter(f -> f.toString().endsWith(".sql")).toList()) {
                Matcher m = CREATE_TABLE.matcher(Files.readString(f));
                while (m.find()) {
                    tables.add(m.group(1));
                }
            }
        }
        return tables;
    }
}
