package com.beomsu.becommerce.seller.screening;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 이름 매칭. <b>여기가 오탐이 나오는 자리</b>라 임계를 정하기 전에 재 둔다.
 *
 * <p>대조에 쓰는 이름은 <b>실제 제재 명단이 아니라</b> 표기 차이를 만들어 보기 위한 것이다.
 * 명단의 내용은 외부에서 받아 갱신하고, 여기서 재는 것은 매칭 방식이다.
 */
@DisplayName("이름 매칭 — 표기 차이는 잡고 다른 이름은 안 잡는가")
class NameMatcherTest {

    private final NameMatcher matcher = new NameMatcher();

    @Test
    @DisplayName("법인격 표기가 달라도 같은 법인으로 본다")
    void ignoresLegalFormNotation() {
        assertThat(matcher.score("주식회사 한빛무역", "한빛무역")).isEqualTo(100);
        assertThat(matcher.score("(주)한빛무역", "한빛무역 주식회사")).isEqualTo(100);
        assertThat(matcher.score("Hanbit Trading Co., Ltd.", "Hanbit Trading")).isEqualTo(100);
    }

    @Test
    @DisplayName("구두점과 공백 차이는 같은 이름으로 본다")
    void ignoresPunctuationAndSpacing() {
        assertThat(matcher.score("Kim, Cheol-Su", "Kim Cheol Su")).isEqualTo(100);
        assertThat(matcher.score("O'Brien  Michael", "OBrien Michael")).isEqualTo(100);
    }

    @Test
    @DisplayName("전각·반각이 섞여도 같은 이름으로 본다")
    void normalizesFullWidth() {
        assertThat(matcher.score("ＡＢＣ 무역", "ABC 무역")).isEqualTo(100);
    }

    @Test
    @DisplayName("한 글자 다르면 점수가 떨어지되 0은 아니다 — 그 구간이 사람이 볼 자리다")
    void nearMissScoresInBetween() {
        int s = matcher.score("김철수", "김철순");
        assertThat(s).isBetween(50, 99);
    }

    @Test
    @DisplayName("긴 이름에서 한 글자 차이가 짧은 이름보다 덜 깎인다 — 절대 거리를 안 쓰는 이유")
    void lengthNormalizedNotAbsolute() {
        int shortName = matcher.score("김철수", "김철순");                    // 3글자 중 1
        int longName = matcher.score("한빛국제무역상사", "한빛국제무역상회");   // 8글자 중 1
        assertThat(longName).isGreaterThan(shortName);
    }

    @Test
    @DisplayName("편집 거리로는 못 가르는 자리가 있다 — 같은 사람의 다른 표기와 다른 사람이 동점이다")
    void editDistanceCannotSeparateTransliterationFromDifferentPerson() {
        // 같은 사람인데 로마자 표기가 다르다
        int sameePersonDifferentRomanization = matcher.score("Kim Cheol Su", "Kim Chul Soo");
        // 다른 사람인데 한 글자 차이다
        int differentPerson = matcher.score("김철수", "김철순");

        // 둘이 같은 점수다. <b>임계를 어디에 둬도 이 둘은 안 갈린다.</b>
        assertThat(sameePersonDifferentRomanization)
                .as("같은 사람의 다른 표기와 다른 사람이 같은 점수를 받는다")
                .isEqualTo(differentPerson);

        // 그래서 문자열 거리만으로는 부족하다. 로마자 표기는 음성학적 매칭이,
        // 동명이인은 생년월일·국적 같은 추가 식별자가 필요하다.
        // 이 한계를 모르고 임계만 조정하면 오탐과 미탐을 맞바꿀 뿐이다.
    }

    @Test
    @DisplayName("전혀 다른 이름은 낮게 나온다")
    void unrelatedNamesScoreLow() {
        assertThat(matcher.score("김철수", "박영희")).isLessThan(40);
        assertThat(matcher.score("한빛무역", "동방물산")).isLessThan(40);
    }

    @Test
    @DisplayName("임계를 어디에 두느냐로 오탐과 미탐이 갈린다 — 그 표를 남긴다")
    void thresholdTradeoff() {
        // 같은 대상(잡아야 함)과 다른 대상(안 잡아야 함)을 섞어 임계별로 센다.
        record Pair(String a, String b, boolean same) {}
        List<Pair> cases = List.of(
                new Pair("주식회사 한빛무역", "한빛무역", true),
                new Pair("Kim, Cheol-Su", "Kim Cheol Su", true),
                new Pair("Hanbit Trading Co., Ltd.", "Hanbit Trading", true),
                // 같은 이름의 다른 로마자 표기. 실제 명단은 표기가 하나로 통일돼 있지 않다.
                new Pair("Kim Cheol Su", "Kim Chul Soo", true),
                new Pair("Lee Jae Myung", "Yi Jae-Myoung", true),
                new Pair("김철수", "김철순", false),          // 흔한 이름의 이웃
                new Pair("한빛무역", "한빛물산", false),
                new Pair("김철수", "박영희", false),
                new Pair("한빛국제무역상사", "동방국제물산상사", false));

        System.out.println("\n╔══ 임계별 오탐·미탐 ══");
        for (int threshold : new int[]{70, 80, 85, 90, 95, 100}) {
            long fp = cases.stream().filter(c -> !c.same() && matcher.score(c.a(), c.b()) >= threshold).count();
            long fn = cases.stream().filter(c -> c.same() && matcher.score(c.a(), c.b()) < threshold).count();
            System.out.printf("  임계 %3d  오탐 %d · 미탐 %d%n", threshold, fp, fn);
        }
        System.out.println("╚═════════════════════");

        System.out.println("\n  개별 점수");
        cases.forEach(c -> System.out.printf("    %-3s %3d  %s ↔ %s%n",
                c.same() ? "같음" : "다름", matcher.score(c.a(), c.b()), c.a(), c.b()));

        // 정규화로 지울 수 있는 차이(법인격·구두점·전각)는 100 이 나온다.
        assertThat(matcher.score("주식회사 한빛무역", "한빛무역")).isEqualTo(100);
        assertThat(matcher.score("Kim, Cheol-Su", "Kim Cheol Su")).isEqualTo(100);
    }
}
