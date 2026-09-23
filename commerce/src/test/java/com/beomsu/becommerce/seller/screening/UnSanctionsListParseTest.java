package com.beomsu.becommerce.seller.screening;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * UN 통합 명단 XML 을 우리가 제대로 읽는지 고정한다.
 *
 * <p>구조는 실제 배포본(<a href="https://scsanctions.un.org/resources/xml/en/consolidated.xml">
 * consolidated.xml</a>)에서 확인한 그대로다 — 이름이 네 칸에 나뉘고, 생년월일에
 * {@code TYPE_OF_DATE} 가 붙고, 개인과 단체가 다른 태그로 온다.
 */
class UnSanctionsListParseTest {

    private static final String XML = """
        <?xml version="1.0" encoding="UTF-8"?>
        <CONSOLIDATED_LIST dateGenerated="2026-09-06T00:00:00Z">
          <INDIVIDUALS>
            <INDIVIDUAL>
              <FIRST_NAME>KIM</FIRST_NAME>
              <SECOND_NAME>CHUL</SECOND_NAME>
              <THIRD_NAME>SOO</THIRD_NAME>
              <UN_LIST_TYPE>DPRK</UN_LIST_TYPE>
              <NATIONALITY>KP</NATIONALITY>
              <INDIVIDUAL_DATE_OF_BIRTH>
                <TYPE_OF_DATE>EXACT</TYPE_OF_DATE>
                <YEAR>1980</YEAR><MONTH>3</MONTH><DAY>15</DAY>
              </INDIVIDUAL_DATE_OF_BIRTH>
            </INDIVIDUAL>
            <INDIVIDUAL>
              <FIRST_NAME>LEE</FIRST_NAME>
              <SECOND_NAME>MIN HO</SECOND_NAME>
              <UN_LIST_TYPE>DPRK</UN_LIST_TYPE>
              <NATIONALITY>IR</NATIONALITY>
              <INDIVIDUAL_DATE_OF_BIRTH>
                <TYPE_OF_DATE>APPROXIMATELY</TYPE_OF_DATE>
                <YEAR>1970</YEAR><MONTH>1</MONTH><DAY>1</DAY>
              </INDIVIDUAL_DATE_OF_BIRTH>
            </INDIVIDUAL>
          </INDIVIDUALS>
          <ENTITIES>
            <ENTITY>
              <FIRST_NAME>DONGBANG TRADING CO</FIRST_NAME>
              <UN_LIST_TYPE>DPRK</UN_LIST_TYPE>
              <NATIONALITY>KP</NATIONALITY>
            </ENTITY>
          </ENTITIES>
        </CONSOLIDATED_LIST>
        """;

    private UnConsolidatedSanctionsList.Snapshot parse() throws Exception {
        return UnConsolidatedSanctionsList.parse(
                new ByteArrayInputStream(XML.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("네 칸에 나뉜 이름을 하나로 붙인다")
    void joinsSplitNames() throws Exception {
        assertThat(parse().entries()).extracting(SanctionsList.Entry::name)
                .contains("KIM CHUL SOO", "LEE MIN HO", "DONGBANG TRADING CO");
    }

    @Test
    @DisplayName("EXACT 인 생년월일만 쓴다 — 어림잡은 날짜로 점수를 깎으면 맞는 사람이 떨어진다")
    void onlyExactBirthDate() throws Exception {
        var entries = parse().entries();
        var exact = entries.stream().filter(e -> e.name().equals("KIM CHUL SOO")).findFirst().orElseThrow();
        var approx = entries.stream().filter(e -> e.name().equals("LEE MIN HO")).findFirst().orElseThrow();

        assertThat(exact.birthDate()).isEqualTo("1980-03-15");
        assertThat(approx.birthDate())
                .as("APPROXIMATELY 는 안 쓴다")
                .isNull();
    }

    @Test
    @DisplayName("단체는 생년월일이 없다")
    void entitiesHaveNoBirthDate() throws Exception {
        var org = parse().entries().stream()
                .filter(e -> e.name().equals("DONGBANG TRADING CO")).findFirst().orElseThrow();
        assertThat(org.birthDate()).isNull();
        assertThat(org.country()).isEqualTo("KP");
    }

    @Test
    @DisplayName("어느 판을 봤는지 남긴다 — '그때는 통과였다'를 대려면 필요하다")
    void recordsVersion() throws Exception {
        assertThat(parse().version()).isEqualTo("UN-2026-09-06T00:00:00Z");
    }

    /** 남이 주는 XML 이라 외부 엔티티를 막아야 한다. 안 막으면 파일을 읽히거나 요청을 대신 보내게 된다. */
    @Test
    @DisplayName("외부 엔티티가 든 XML 은 거부한다")
    void rejectsExternalEntities() {
        String xxe = """
            <?xml version="1.0"?>
            <!DOCTYPE r [<!ENTITY x SYSTEM "file:///etc/passwd">]>
            <CONSOLIDATED_LIST dateGenerated="x"><INDIVIDUALS><INDIVIDUAL>
            <FIRST_NAME>&x;</FIRST_NAME></INDIVIDUAL></INDIVIDUALS></CONSOLIDATED_LIST>
            """;
        assertThatThrownBy(() -> UnConsolidatedSanctionsList.parse(
                new ByteArrayInputStream(xxe.getBytes(StandardCharsets.UTF_8))))
                .as("DOCTYPE 선언 자체를 막는다")
                .isInstanceOf(Exception.class);
    }
}
