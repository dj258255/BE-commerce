package com.beomsu.becommerce.seller.screening;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 한글 이름을 로마자 후보로 펼쳐 <b>편집 거리로 못 가르던 둘</b>을 가르는지 잰다.
 *
 * <p>앞서 임계를 고르려다 막혔다 — {@code Kim Cheol Su ↔ Kim Chul Soo}(같은 사람)와
 * {@code 김철수 ↔ 김철순}(다른 사람)이 <b>둘 다 67점</b>이라 임계를 어디에 둬도 안 갈렸다.
 * 그때 결론이 "문자열 거리의 한계"였는데, 실제 명단을 보고 나니 다른 이야기였다 —
 * <b>제재 명단에 한글이 한 건도 없고</b>(UN 통합 명단 실측 0건), 한국 관련 79건은 전부
 * {@code RI JE-SON}·{@code PAK}·{@code CHOE} 같은 로마자다. 대조할 표기가 없었던 것이다.
 */
class KoreanRomanizerTest {

    private final NameMatcher matcher = new NameMatcher();

    /** 후보 중 가장 높은 점수. 실제 스크리닝이 하는 것과 같은 방식이다. */
    private int best(String hangul, String listed) {
        Set<String> cands = KoreanRomanizer.romanize(hangul);
        return cands.stream().mapToInt(c -> matcher.score(c, listed)).max().orElse(0);
    }

    @Test
    @DisplayName("김철수를 로마자 후보로 펼친다")
    void expandsCandidates() {
        Set<String> c = KoreanRomanizer.romanize("김철수");
        System.out.printf("%n  김철수 → %d개%n", c.size());
        c.stream().limit(10).forEach(s -> System.out.printf("    %s%n", s));

        assertThat(c).isNotEmpty();
        assertThat(c).as("남한 통용 표기").anyMatch(s -> s.startsWith("KIM"));
        assertThat(c).as("규칙(RR) 표기도 낸다").anyMatch(s -> s.startsWith("GIM"));
    }

    @Test
    @DisplayName("점수만 올리면 남도 같이 올라간다 — 그래서 정확 일치를 본다")
    void scoreAloneLiftsEveryone() {
        String listed = "KIM CHUL SOO";
        int same = best("김철수", listed);
        int other = best("김철순", listed);

        System.out.printf("%n  대조 대상: %s%n", listed);
        System.out.printf("  펼치기 전 — 같은 사람 %d점 · 다른 사람 %d점 (안 갈림)%n",
                matcher.score("Kim Cheol Su", listed), matcher.score("김철수", "김철순"));
        System.out.printf("  점수만 보면 — 김철수 %d점 · 김철순 %d점  (%s)%n",
                same, other, same - other > 15 ? "갈림" : "여전히 붙어 있다");

        assertThat(same - other)
                .as("후보를 늘린 만큼 남에게도 잘 걸린다 — 점수로는 못 가른다")
                .isLessThanOrEqualTo(15);
    }

    @Test
    @DisplayName("정확히 맞는 후보가 있는지로 가른다")
    void exactCandidateSeparates() {
        String listed = "KIM CHUL SOO";
        String hit = KoreanRomanizer.exactMatch("김철수", listed);
        String miss = KoreanRomanizer.exactMatch("김철순", listed);

        System.out.printf("%n  정확 일치 — 김철수 → %s%n", hit);
        System.out.printf("             김철순 → %s%n", miss);

        assertThat(hit).as("같은 사람은 후보 중 하나가 딱 맞는다").isNotNull();
        assertThat(miss).as("다른 사람은 어느 후보도 안 맞는다").isNull();
    }

    /** 명단에 실제로 있는 북한식 표기들. 남한 표기와 다르다. */
    @Test
    @DisplayName("명단의 북한식 표기에도 걸린다")
    void matchesNorthKoreanStyle() {
        record Case(String hangul, String listed) {}
        List<Case> cases = List.of(
                new Case("이제선", "RI JE SON"),
                new Case("박창호", "PAK CHANG HO"),
                new Case("최영건", "CHOE YONG GON"),
                new Case("백창호", "PAEK CHANG HO"));

        System.out.printf("%n  %-10s %-18s %s%n", "한글", "명단 표기", "점수");
        System.out.println("  " + "-".repeat(40));
        for (Case c : cases) {
            int s = best(c.hangul(), c.listed());
            System.out.printf("  %-10s %-18s %d점%n", c.hangul(), c.listed(), s);
            String exact = KoreanRomanizer.exactMatch(c.hangul(), c.listed());
            System.out.printf("      정확 일치: %s%n", exact == null ? "없음" : exact);
            assertThat(exact)
                    .as("%s 가 %s 에 안 걸리면 한국인 판매자를 못 본다", c.hangul(), c.listed())
                    .isNotNull();
        }
    }

    @Test
    @DisplayName("한글이 없으면 빈 집합 — 원본을 그대로 쓰면 된다")
    void nonHangulYieldsNothing() {
        assertThat(KoreanRomanizer.romanize("DONGBANG TRADING CO")).isEmpty();
        assertThat(KoreanRomanizer.hasHangul("KIM CHUL SOO")).isFalse();
    }

    @Test
    @DisplayName("두 글자 성을 한 글자로 자르지 않는다")
    void twoCharSurname() {
        var c = KoreanRomanizer.romanize("남궁철수");
        System.out.printf("%n  남궁철수 → %s%n", c.stream().limit(4).toList());
        assertThat(c).anyMatch(s -> s.startsWith("NAMGUNG") || s.startsWith("NAMKUNG"));
        assertThat(c).as("남 + 궁철수 로 자르면 안 된다").noneMatch(s -> s.startsWith("NAM KUNG C"));
    }

    @Test
    @DisplayName("성이 뒤에 오는 표기도 만든다 — 명단마다 순서가 다르다")
    void reversedOrder() {
        var c = KoreanRomanizer.romanize("김철수");
        assertThat(c).as("한국식 순서").anyMatch(s -> s.startsWith("KIM"));
        assertThat(c).as("서양식 순서").anyMatch(s -> s.endsWith("KIM"));
    }

    @Test
    @DisplayName("후보에 없는 표기는 소리로 잡는다 — 다만 확정은 안 한다")
    void phoneticCatchesWhatCandidatesMiss() {
        // Bahk 은 후보에 없다. 그래도 소리는 같다.
        boolean inCandidates = KoreanRomanizer.romanize("박창호").stream()
                .anyMatch(s -> s.replace(" ", "").equalsIgnoreCase("BAHKCHANGHO"));
        boolean sameSound = KoreanRomanizer.romanize("박창호").stream()
                .anyMatch(s -> PhoneticKey.sounds(s, "BAHK CHANG HO"));
        System.out.printf("%n  BAHK CHANG HO — 후보에 있나 %s · 소리가 같나 %s%n", inCandidates, sameSound);
        assertThat(sameSound).as("후보를 다 못 담으므로 소리가 메꾼다").isTrue();
    }
}
