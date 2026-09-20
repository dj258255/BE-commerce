package com.beomsu.becommerce;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
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
 * API 스펙 문서의 에러 코드가 <b>실제로 나가는 코드</b>인지 대조한다.
 *
 * <p><b>왜 생겼나</b>: 스펙이 {@code CONCURRENT_MODIFICATION}, {@code PG_ERROR},
 * {@code PG_TIMEOUT} 셋을 문서화하고 있었는데 <b>코드 어디에서도 던지지 않았다.</b>
 * 낙관적 락 충돌은 실제로 {@code STOCK_CONCURRENCY}/{@code WALLET_CONCURRENCY} 로 나간다.
 *
 * <p>클라이언트 입장에서 이건 <b>두 번 손해</b>다 — 오지 않을 코드로 분기를 만들고,
 * 실제로 오는 코드는 문서에 없어서 못 다룬다. 결제 API 에서 이건 재시도 로직이
 * 통째로 어긋난다는 뜻이다.
 *
 * <p>같은 발상의 검사가 이미 둘 있다 — ERD 문서 대 마이그레이션({@code ErdDocMatchesSchemaTest}),
 * 원인 분류 수 대 규칙({@code CauseCoverageHarnessTest}). <b>문서가 코드보다 앞서가는 것을
 * 사람이 알아채길 기다리지 않는다.</b>
 */
class ApiSpecErrorCodesTest {

    private static final Path SPEC = Path.of("docs/10-API-스펙.md");
    private static final Path SRC = Path.of("src/main/java");

    /** 표의 {@code | 400 | `CODE` | 설명 |} 행에서 코드만 뽑는다. */
    private static final Pattern TABLE_CODE = Pattern.compile(
            "^\\|\\s*\\d{3}\\s*\\|(.+?)\\|", Pattern.MULTILINE);
    private static final Pattern BACKTICKED = Pattern.compile("`([A-Z][A-Z0-9_]{3,})`");

    /** 코드에서 실제로 던지는 문자열 상수. */
    private static final Pattern THROWN = Pattern.compile("\"([A-Z][A-Z0-9_]{3,})\"");

    @Test
    @DisplayName("스펙이 적은 에러 코드는 전부 코드가 실제로 던지는 것이어야 한다")
    void everyDocumentedCodeIsActuallyThrown() {
        Set<String> documented = documentedCodes();
        Set<String> thrown = thrownCodes();

        assertThat(documented)
                .as("스펙 표에서 코드를 하나도 못 뽑았다면 표 형식이 바뀐 것이다")
                .isNotEmpty();

        List<String> ghosts = documented.stream().filter(c -> !thrown.contains(c)).sorted().toList();
        assertThat(ghosts)
                .as("""
                        스펙에만 있고 코드에서 던지지 않는 에러 코드다.
                        클라이언트가 오지 않을 분기를 만든다 — 문서를 고치거나 코드를 맞춰라.""")
                .isEmpty();
    }

    private static Set<String> documentedCodes() {
        String text = read(SPEC);
        Set<String> out = new LinkedHashSet<>();
        Matcher rows = TABLE_CODE.matcher(text);
        while (rows.find()) {
            Matcher codes = BACKTICKED.matcher(rows.group(1));
            while (codes.find()) {
                out.add(codes.group(1));
            }
        }
        return out;
    }

    private static Set<String> thrownCodes() {
        Set<String> out = new LinkedHashSet<>();
        try (Stream<Path> files = Files.walk(SRC)) {
            List<Path> javas = files.filter(p -> p.toString().endsWith(".java")).toList();
            for (Path p : javas) {
                Matcher m = THROWN.matcher(read(p));
                while (m.find()) {
                    out.add(m.group(1));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("읽을 수 없다: " + p.toAbsolutePath(), e);
        }
    }

    @Test
    @DisplayName("검사가 실제로 잡는지 확인한다 — 없는 코드를 넣으면 걸려야 한다")
    void detectorActuallyDetects() {
        Set<String> thrown = thrownCodes();
        List<String> fake = new ArrayList<>(List.of("PG_TIMEOUT", "CONCURRENT_MODIFICATION"));
        fake.removeIf(thrown::contains);

        assertThat(fake)
                .as("한때 스펙에 있던 유령 코드들. 코드에 생겼다면 이 테스트를 갱신하라")
                .hasSize(2);
    }
}
