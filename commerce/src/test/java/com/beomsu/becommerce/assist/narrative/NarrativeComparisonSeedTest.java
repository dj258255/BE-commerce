package com.beomsu.becommerce.assist.narrative;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 미리 뽑아 둔 블라인드 표본을 {@code narrative_preferences} 에 <b>안 고른 상태로</b> 넣는다.
 *
 * <p><b>왜 필요한가</b>: 비교를 그 자리에서 만들면 모델 호출이 건당 10초 안팎이라, 30건을 고르는
 * 동안 클릭 사이마다 그만큼 기다린다. 표본이 0건이던 실제 이유가 판단의 어려움이 아니라 그
 * 대기였다. 만드는 일과 고르는 일을 갈라, 만들어 두고 사람은 클릭만 하게 한다.
 *
 * <p><b>고른 값은 넣지 않는다.</b> {@code choice} 와 {@code reviewer} 를 비운 채로 넣는다.
 * 승격 기준은 <b>사람이 고른</b> 25~30건이므로, 여기서 미리 채우면 그 기준이 무의미해진다.
 *
 * <p>표본과 정답 키는 {@code src/test/resources/narrative-blind/} 에 있다. A/B 배정은 그 파일을
 * 만들 때 이미 무작위로 섞였고, 키는 고르기 전에는 열지 않는다.
 */
@Tag("capture")
@DisplayName("쌍 비교 표본 심기 — 만드는 일과 고르는 일을 가른다")
class NarrativeComparisonSeedTest {

    private static final Path DIR = Path.of("src/test/resources/narrative-blind");

    private record Pair(String caseId, String sourceA, String sourceB, String textA, String textB) {}

    @Test
    @DisplayName("blind.txt 와 key.csv 를 읽어 안 고른 상태로 넣는다")
    void seed() throws Exception {
        String url = System.getProperty("seed.url", "jdbc:mysql://localhost:3306/becommerce?serverTimezone=UTC");
        String user = System.getProperty("seed.user", "root");
        String pass = System.getProperty("seed.pass", "root");

        List<Pair> pairs = readPairs();
        try (Connection c = DriverManager.getConnection(url, user, pass);
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO narrative_preferences "
                             + "(order_no, source_a, source_b, text_a, text_b, created_at) "
                             + "VALUES (?, ?, ?, ?, ?, ?)")) {
            for (Pair p : pairs) {
                ps.setString(1, "BLIND-" + p.caseId());
                ps.setString(2, p.sourceA());
                ps.setString(3, p.sourceB());
                ps.setString(4, p.textA());
                ps.setString(5, p.textB());
                ps.setTimestamp(6, java.sql.Timestamp.from(Instant.now()));
                ps.addBatch();
            }
            int[] done = ps.executeBatch();
            System.out.println("심은 표본 " + done.length + "건 — 전부 안 고른 상태다");
            System.out.println("  앱을 띄우고 /narrative-compare.html 에서 고르면 된다");
        }
    }

    /** 가린 본문에서 A·B 를 읽고, 출처는 키에서 읽어 DB 에만 넣는다(화면에는 안 나간다). */
    private List<Pair> readPairs() throws Exception {
        String blind = Files.readString(DIR.resolve("blind.txt"), StandardCharsets.UTF_8);
        List<String> keyLines = Files.readAllLines(DIR.resolve("key.csv"), StandardCharsets.UTF_8);

        List<Pair> out = new ArrayList<>();
        for (int i = 1; i < keyLines.size(); i++) {
            String[] k = keyLines.get(i).split(",");
            if (k.length < 3) {
                continue;
            }
            String id = k[0];
            String block = between(blind, "=== " + id + " ===", "\n=== ");
            if (block == null) {
                continue;
            }
            String a = between(block, "[A]", "\n[B]");
            String b = block.substring(block.indexOf("[B]") + 3);
            if (a == null) {
                continue;
            }
            out.add(new Pair(id, k[1].strip(), k[2].strip(), a.strip(), b.strip()));
        }
        return out;
    }

    private String between(String text, String start, String end) {
        int s = text.indexOf(start);
        if (s < 0) {
            return null;
        }
        s += start.length();
        int e = text.indexOf(end, s);
        return e < 0 ? text.substring(s) : text.substring(s, e);
    }
}
