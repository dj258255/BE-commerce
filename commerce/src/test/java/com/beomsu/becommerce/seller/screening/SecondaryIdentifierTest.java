package com.beomsu.becommerce.seller.screening;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 이름만으로는 못 가르는 자리를 <b>2차 식별자</b>가 가르는지 잰다.
 *
 * <p><b>왜 다시 재나</b>: 앞서 임계를 고르려고 이름 점수를 쟀더니
 * <b>같은 사람의 다른 로마자 표기와 아예 다른 사람이 같은 점수</b>를 받았다. 임계를 어디에 둬도
 * 그 둘은 안 갈린다고 적고 사람 검토로 보냈다. 그런데 그때 결론이 <b>"문자열 거리의 한계"</b>였다.
 *
 * <p>업계 자료를 보니 그게 아니었다. 제재 스크리닝은 세 층으로 한다 — 정확 일치, 퍼지 매칭,
 * 그리고 <b>생년월일·국적·문서번호로 후보를 좁히는 맥락 판별</b>이다. 경보의 90% 이상이
 * 오탐이라 세 번째 층이 실무의 핵심인데, <b>내가 만든 것은 두 번째 층까지였다.</b>
 * 명단 항목이 이름·프로그램·국가만 들고 있어서 애초에 좁힐 재료가 없었다.
 *
 * <p>그러니 못 가른 이유는 문자열 거리가 약해서가 아니라 <b>볼 것을 안 보고 있어서</b>다.
 * 이 테스트가 그 주장을 검사한다.
 */
class SecondaryIdentifierTest {

    private final NameMatcher matcher = new NameMatcher();

    /** 앞선 결론을 그대로 재현한다 — 이름만 보면 둘이 안 갈린다. */
    @Test
    @DisplayName("이름만 보면 같은 사람과 다른 사람이 같은 점수를 받는다")
    void nameAloneCannotSeparate() {
        int samePerson = matcher.score("Kim Cheol Su", "Kim Chul Soo");   // 같은 사람, 다른 로마자
        int different  = matcher.score("김철수", "김철순");                  // 다른 사람, 한 글자 차이

        System.out.printf("%n  같은 사람 다른 표기  Kim Cheol Su ↔ Kim Chul Soo   %d점%n", samePerson);
        System.out.printf("  다른 사람 비슷한 이름 김철수 ↔ 김철순              %d점%n", different);

        assertThat(Math.abs(samePerson - different))
                .as("두 점수가 붙어 있으면 임계를 어디에 둬도 못 가른다")
                .isLessThanOrEqualTo(10);
    }

    /**
     * 2차 식별자를 넣으면 갈리는가. 실제 제재 목록(OFAC SDN)은 이름과 함께
     * 생년월일·국적·문서번호를 준다. 그걸 쓰면 같은 이름이라도 다른 사람은 떨어져 나간다.
     */
    @Test
    @DisplayName("생년월일·국적을 함께 보면 갈린다")
    void secondaryIdentifiersSeparate() {
        var entry = new SanctionsList.Entry("Kim Chul Soo", "SDN", "KP");

        // 같은 사람 — 생년월일과 국적이 맞는다
        var same = SecondaryIdentifiers.of("1980-03-15", "KP");
        // 다른 사람 — 이름은 비슷한데 생년월일도 국적도 다르다
        var other = SecondaryIdentifiers.of("1992-11-02", "KR");

        var entryIds = SecondaryIdentifiers.of("1980-03-15", "KP");

        int nameScore = matcher.score("Kim Cheol Su", entry.name());
        int withSame  = SecondaryIdentifiers.adjust(nameScore, same, entryIds);
        int withOther = SecondaryIdentifiers.adjust(nameScore, other, entryIds);

        System.out.printf("%n  이름 점수만                      %d점%n", nameScore);
        System.out.printf("  + 생년월일·국적 일치             %d점%n", withSame);
        System.out.printf("  + 생년월일·국적 불일치           %d점%n", withOther);

        assertThat(withSame).as("2차 식별자가 맞으면 올라간다").isGreaterThan(nameScore);
        assertThat(withOther).as("2차 식별자가 어긋나면 임계 아래로 떨어진다").isLessThan(80);
        assertThat(withSame - withOther)
                .as("이름만 볼 때는 붙어 있던 둘이 갈려야 한다")
                .isGreaterThan(30);
    }

    /** 2차 식별자를 <b>모를 때</b>는 점수를 안 건드린다. 모르는 것을 아는 것처럼 쓰면 안 된다. */
    @Test
    @DisplayName("2차 식별자를 모르면 이름 점수를 그대로 둔다")
    void unknownIdentifiersDoNotChangeScore() {
        var entryIds = SecondaryIdentifiers.of("1980-03-15", "KP");
        int nameScore = 85;

        assertThat(SecondaryIdentifiers.adjust(nameScore, SecondaryIdentifiers.unknown(), entryIds))
                .as("우리가 모르면 그대로")
                .isEqualTo(nameScore);
        assertThat(SecondaryIdentifiers.adjust(nameScore, entryIds, SecondaryIdentifiers.unknown()))
                .as("명단이 안 주면 그대로")
                .isEqualTo(nameScore);
    }

    /** 생년월일 하나만 어긋나도 떨어뜨린다 — 실무에서 가장 강한 신호다. */
    @Test
    @DisplayName("생년월일만 어긋나도 임계 아래로 떨어진다")
    void dobMismatchAloneIsDecisive() {
        var ours  = SecondaryIdentifiers.of("1992-11-02", null);
        var theirs = SecondaryIdentifiers.of("1980-03-15", null);
        assertThat(SecondaryIdentifiers.adjust(95, ours, theirs)).isLessThan(80);
    }

    /** 임계를 고를 수 있게 됐는지 표로 남긴다. */
    @Test
    @DisplayName("임계 표 — 2차 식별자 전후")
    void thresholdTable() {
        record Case(String label, String ours, String theirs, String ourDob, String theirDob,
                    String ourNat, String theirNat, boolean shouldMatch) {}
        List<Case> cases = List.of(
                new Case("같은 사람 다른 로마자", "Kim Cheol Su", "Kim Chul Soo", "1980-03-15", "1980-03-15", "KP", "KP", true),
                new Case("다른 사람 비슷한 이름", "김철수", "김철순", "1992-11-02", "1980-03-15", "KR", "KP", false),
                new Case("같은 사람 띄어쓰기만", "KimChulSoo", "Kim Chul Soo", "1980-03-15", "1980-03-15", "KP", "KP", true),
                new Case("흔한 이름 남남", "Lee Min Ho", "Lee Min Ho", "1995-07-07", "1970-01-01", "KR", "IR", false)
        );
        System.out.printf("%n  %-22s %8s %10s  %s%n", "사례", "이름만", "+식별자", "판정");
        System.out.println("  " + "-".repeat(58));
        int wrongBefore = 0, wrongAfter = 0;
        for (Case c : cases) {
            int name = matcher.score(c.ours(), c.theirs());
            int adj = SecondaryIdentifiers.adjust(name,
                    SecondaryIdentifiers.of(c.ourDob(), c.ourNat()),
                    SecondaryIdentifiers.of(c.theirDob(), c.theirNat()));
            boolean beforeHit = name >= 80, afterHit = adj >= 80;
            if (beforeHit != c.shouldMatch()) wrongBefore++;
            if (afterHit != c.shouldMatch()) wrongAfter++;
            System.out.printf("  %-22s %7d점 %9d점  %s%n", c.label(), name, adj,
                    afterHit == c.shouldMatch() ? "맞음" : "틀림");
        }
        System.out.printf("%n  임계 80 기준 오판  이름만 %d/4 → 식별자 포함 %d/4%n", wrongBefore, wrongAfter);
        assertThat(wrongAfter).isLessThan(wrongBefore);
    }

    /**
     * 실제 명단은 <b>전체 날짜를 안 준다.</b> UN 통합 명단을 받아 세어 보니 EXACT 751건 중
     * 연월일이 다 있는 것은 <b>0건</b>이고 263건이 연도만 준다. 그래서 겹치는 자리까지만 본다.
     */
    @Test
    @DisplayName("명단이 연도만 주면 연도만 맞댄다 — 그게 실제 데이터가 주는 전부다")
    void partialBirthDateComparesOverlapOnly() {
        var ours = SecondaryIdentifiers.of("1971-04-02", "KP");
        var listYearOnly = SecondaryIdentifiers.of("1971", "KP");
        var listOtherYear = SecondaryIdentifiers.of("1985", "KP");

        int base = 70;
        int same = SecondaryIdentifiers.adjust(base, ours, listYearOnly);
        int diff = SecondaryIdentifiers.adjust(base, ours, listOtherYear);

        System.out.printf("%n  이름 %d점 · 명단이 연도만 줄 때%n", base);
        System.out.printf("    연도 같음  %d점%n", same);
        System.out.printf("    연도 다름  %d점%n", diff);

        assertThat(same).as("연도가 맞으면 올라간다").isGreaterThan(base);
        assertThat(diff).as("연도가 다르면 떨어진다").isLessThan(base);
        assertThat(same - base)
                .as("연도만 맞은 것은 전체가 맞은 것보다 약한 증거다")
                .isLessThan(SecondaryIdentifiers.adjust(base, ours, SecondaryIdentifiers.of("1971-04-02", "KP")) - base);
    }
}
