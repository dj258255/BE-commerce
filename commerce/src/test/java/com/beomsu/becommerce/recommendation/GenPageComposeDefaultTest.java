package com.beomsu.becommerce.recommendation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code app.recommendation.genpage-compose} 의 기본값을 고정한다(ADR-079, S4 #467).
 *
 * <p>기본값은 {@code application.yml} 플레이스홀더에 있다. 여기서 지키려는 것은 값 하나다 —
 * 환경변수가 없을 때 홈 2쪽 줄 구성이 결합 방식으로 켜져 있어야 한다. 값이 다시 꺼지면 이 테스트가
 * 먼저 알려 준다. 결합 방식의 동작 자체는 {@link RecommendationFactsComposeTest} 가 {@code OFF} 를
 * 명시 설정으로 주고 따로 지킨다.
 */
class GenPageComposeDefaultTest {

    @Test
    @DisplayName("application.yml 기본값은 HYBRID 다 — 환경변수가 없을 때 켜진다(ADR-079)")
    void defaultIsHybrid() throws IOException {
        String value = ymlDefault("genpage-compose");

        assertThat(value).isEqualTo("HYBRID");
        assertThat(RecommendationFacts.GenPageCompose.valueOf(value))
                .isEqualTo(RecommendationFacts.GenPageCompose.HYBRID);
    }

    /** {@code application.yml} 의 {@code genpage-compose} 플레이스홀더 기본값. */
    private static String ymlDefault(String key) throws IOException {
        try (InputStream in = GenPageComposeDefaultTest.class.getResourceAsStream("/application.yml")) {
            assertThat(in).as("application.yml 을 찾지 못했다").isNotNull();
            String yml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("^\\s*" + key + ":\\s*\\$\\{[A-Z_]+:([^}]*)}", Pattern.MULTILINE)
                    .matcher(yml);
            assertThat(m.find()).as("application.yml 에서 %s 를 못 찾았다", key).isTrue();
            return m.group(1);
        }
    }
}
