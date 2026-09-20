package com.beomsu.becommerce.seller.screening;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 한글 이름을 <b>로마자 표기 후보 여럿</b>으로 펼친다.
 *
 * <h3>왜 필요한가</h3>
 * 우리 판매자는 한글로 들어오는데 <b>제재 명단에 한글이 한 건도 없다</b>(UN 통합 명단 실측 0건).
 * 한국 관련 항목 79건은 전부 로마자인데, 그것도 {@code RI JE-SON}·{@code PAK}·{@code CHOE} 처럼
 * <b>남한에서 쓰는 표기와 다르다</b> — 같은 성이 남한에서는 Lee·Park·Choi 다.
 *
 * <h3>왜 편집 거리로는 안 되나</h3>
 * 임계를 고르려고 재 봤더니 <b>같은 사람의 다른 표기와 아예 다른 사람이 같은 점수</b>였다.
 * {@code Kim Cheol Su ↔ Kim Chul Soo}(같은 사람)와 {@code 김철수 ↔ 김철순}(다른 사람)이 둘 다 67점.
 * 거리를 아무리 잘 재도 이 둘은 안 갈린다 — <b>거리의 문제가 아니라 대조할 표기가 없는 문제</b>다.
 *
 * <p>그래서 한글에서 <b>쓸 법한 표기를 미리 만들어</b> 그중 하나라도 맞는지 본다.
 * 김철수는 {@code KIM CHEOL SU}·{@code KIM CHUL SOO}·{@code GIM CHEOL SU} …를 내고,
 * 김철순은 {@code ... SUN}·{@code ... SOON} 을 낸다. 명단의 {@code KIM CHUL SOO} 는
 * 앞에는 <b>정확히</b> 걸리고 뒤에는 안 걸린다.
 *
 * <h3>완전하지 않다는 것을 적어 둔다</h3>
 * 국어의 로마자 표기법(RR)과 통용 표기, 북한식(MR 계열)을 섞어 후보를 낸다.
 * 사람이 실제로 쓰는 표기는 더 다양하고(같은 박씨가 Park·Bak·Pak·Bahk 를 쓴다),
 * <b>여기서 만드는 것은 그중 흔한 것들일 뿐</b>이다. 후보에 없으면 못 맞춘다.
 */
public final class KoreanRomanizer {

    private static final char BASE = 0xAC00, LAST = 0xD7A3;

    /** 초성 19개. 성씨에서 흔히 갈리는 것만 여러 표기를 준다(ㄱ→G/K, ㅂ→B/P, ㅈ→J/CH). */
    private static final String[][] ONSET = {
            {"G", "K"}, {"KK", "GG"}, {"N"}, {"D", "T"}, {"TT"}, {"R", "L"}, {"M"},
            {"B", "P"}, {"PP"}, {"S"}, {"SS"}, {""}, {"J", "CH"}, {"JJ"}, {"CH"},
            {"K"}, {"T"}, {"P"}, {"H"}
    };

    /** 중성 21개. 흔한 이형 표기를 함께 낸다(ㅓ→EO/U, ㅜ→U/OO, ㅚ→OE/OI). */
    private static final String[][] NUCLEUS = {
            {"A"}, {"AE"}, {"YA"}, {"YAE"}, {"EO", "U", "O"}, {"E"}, {"YEO", "YO", "YU"}, {"YE"},
            {"O"}, {"WA"}, {"WAE"}, {"OE", "OI", "WE"}, {"YO"}, {"U", "OO"}, {"WO"}, {"WE"},
            {"WI"}, {"YU"}, {"EU", "U"}, {"UI"}, {"I", "EE"}
    };

    /**
     * 종성 28개. 순서는 유니코드 한글 조합 규칙 그대로다 —
     * {@code 없음 ㄱ ㄲ ㄳ ㄴ ㄵ ㄶ ㄷ ㄹ ㄺ ㄻ ㄼ ㄽ ㄾ ㄿ ㅀ ㅁ ㅂ ㅄ ㅅ ㅆ ㅇ ㅈ ㅊ ㅋ ㅌ ㅍ ㅎ}.
     *
     * <p><b>이 표를 한 칸 밀려 적어서</b> {@code 창}의 받침 ㅇ 이 {@code T} 로 나왔고,
     * {@code 박창호 → PAK CHANG HO} 가 안 걸렸다. 자모 인덱스를 찍어 보고서야 찾았다 —
     * 눈으로 세면 틀린다.
     */
    private static final String[][] CODA = {
            {""},            // 없음
            {"K", "G"},      // ㄱ
            {"K"},           // ㄲ
            {"K"},           // ㄳ
            {"N"},           // ㄴ
            {"N"},           // ㄵ
            {"N"},           // ㄶ
            {"T"},           // ㄷ
            {"L", "R"},      // ㄹ
            {"K"},           // ㄺ
            {"M"},           // ㄻ
            {"P"},           // ㄼ
            {"L"},           // ㄽ
            {"L"},           // ㄾ
            {"P"},           // ㄿ
            {"L"},           // ㅀ
            {"M"},           // ㅁ
            {"P", "B"},      // ㅂ
            {"P"},           // ㅄ
            {"T", "S"},      // ㅅ
            {"T"},           // ㅆ
            {"NG"},          // ㅇ
            {"T"},           // ㅈ
            {"T"},           // ㅊ
            {"K"},           // ㅋ
            {"T"},           // ㅌ
            {"P"},           // ㅍ
            {"T"}            // ㅎ
    };

    /**
     * 성씨는 규칙대로 안 쓴다. 실제로 통용되는 표기를 따로 둔다 —
     * 이(李)를 규칙대로 펼치면 {@code I} 가 나오는데 아무도 그렇게 안 쓴다.
     */
    private static final Map<Character, List<String>> SURNAME = Map.ofEntries(
            Map.entry('김', List.of("KIM", "GIM")),
            Map.entry('이', List.of("LEE", "RI", "YI", "I")),
            Map.entry('박', List.of("PARK", "PAK", "BAK", "BACK")),
            Map.entry('최', List.of("CHOI", "CHOE")),
            Map.entry('정', List.of("JUNG", "JEONG", "CHUNG", "JONG")),
            Map.entry('강', List.of("KANG", "GANG")),
            Map.entry('조', List.of("CHO", "JO")),
            Map.entry('윤', List.of("YOON", "YUN")),
            Map.entry('장', List.of("JANG", "CHANG")),
            Map.entry('류', List.of("RYU", "YU", "RIU")),
            Map.entry('노', List.of("NOH", "NO", "RO")),
            Map.entry('전', List.of("JEON", "CHON", "JUN")),
            Map.entry('현', List.of("HYUN", "HYON")),
            Map.entry('허', List.of("HEO", "HUH", "HO")),
            Map.entry('남', List.of("NAM")),
            Map.entry('유', List.of("YU", "YOO", "RYU")),
            Map.entry('임', List.of("LIM", "IM", "RIM")),
            Map.entry('한', List.of("HAN")),
            Map.entry('오', List.of("OH", "O")),
            Map.entry('서', List.of("SEO", "SUH", "SO")),
            Map.entry('신', List.of("SHIN", "SIN")),
            Map.entry('권', List.of("KWON", "GWON")),
            Map.entry('황', List.of("HWANG")),
            Map.entry('안', List.of("AHN", "AN")),
            Map.entry('송', List.of("SONG")),
            Map.entry('백', List.of("BAEK", "PAEK", "BAIK")),
            Map.entry('문', List.of("MOON", "MUN")),
            Map.entry('손', List.of("SON", "SOHN")),
            Map.entry('고', List.of("KO", "GO")),
            Map.entry('배', List.of("BAE", "PAE", "BAY")),
            Map.entry('양', List.of("YANG")),
            Map.entry('구', List.of("KOO", "GU", "KU")),
            Map.entry('성', List.of("SUNG", "SEONG", "SONG")),
            Map.entry('차', List.of("CHA")),
            Map.entry('주', List.of("JOO", "JU", "CHU")),
            Map.entry('우', List.of("WOO", "U", "OO")),
            Map.entry('민', List.of("MIN")),
            Map.entry('나', List.of("NA", "RA")),
            Map.entry('라', List.of("RA", "NA")),
            Map.entry('심', List.of("SHIM", "SIM")),
            Map.entry('하', List.of("HA")),
            Map.entry('곽', List.of("KWAK", "GWAK")),
            Map.entry('홍', List.of("HONG")),
            Map.entry('여', List.of("YEO", "YO", "YU")),
            Map.entry('연', List.of("YEON", "YON")),
            Map.entry('명', List.of("MYUNG", "MYONG")),
            Map.entry('진', List.of("JIN", "CHIN")),
            Map.entry('원', List.of("WON")),
            Map.entry('천', List.of("CHUN", "CHON", "CHEON")));

    /**
     * 두 글자 성. 안 다루면 <b>성을 한 글자로 잘라</b> 엉뚱하게 펼친다 —
     * 남궁철수를 "남" + "궁철수"로 읽는다.
     */
    private static final Map<String, List<String>> TWO_CHAR_SURNAME = Map.of(
            "남궁", List.of("NAMGUNG", "NAMKUNG", "NAM GUNG"),
            "선우", List.of("SUNWOO", "SEONU", "SUN WOO"),
            "황보", List.of("HWANGBO", "HWANG BO"),
            "제갈", List.of("JEGAL", "CHEGAL"),
            "사공", List.of("SAGONG", "SAKONG"),
            "독고", List.of("DOKGO", "TOKKO"),
            "서문", List.of("SEOMUN", "SUHMOON"));

    /**
     * 후보 상한.
     *
     * <p>처음엔 48로 잡았다. <b>많이 만들수록 엉뚱한 사람에게도 걸린다</b>고 봤기 때문인데,
     * 그건 점수를 재던 때의 걱정이다. 지금은 <b>정확 일치</b>만 보므로 후보가 많다고
     * 남에게 붙지 않는다 — 딱 맞거나 안 맞거나다. 낮은 상한이 오히려
     * {@code 박창호 → PAK CHANG HO} 를 잘라 <b>진짜 대상을 놓치고 있었다.</b>
     */
    private static final int MAX = 512;

    private KoreanRomanizer() {}

    /** 한글이 섞여 있나. 아니면 펼칠 것이 없다. */
    public static boolean hasHangul(String s) {
        return s != null && s.chars().anyMatch(c -> c >= BASE && c <= LAST);
    }

    /**
     * 로마자 표기 후보를 만든다. 한글이 없으면 <b>빈 집합</b>을 낸다 — 원본을 그대로 쓰면 된다.
     *
     * @param name 예: {@code 김철수}
     * @return 예: {@code [KIM CHEOL SU, KIM CHUL SOO, GIM CHEOL SU, ...]}
     */
    public static Set<String> romanize(String name) {
        if (!hasHangul(name)) return Set.of();
        String clean = name.replaceAll("[^가-힣]", "");
        if (clean.isEmpty()) return Set.of();

        // 두 글자 성을 먼저 본다. 안 보면 남궁철수를 "남" + "궁철수"로 잘라 엉뚱하게 펼친다.
        String head2 = clean.length() >= 3 ? clean.substring(0, 2) : null;
        List<String> surnames;
        String rest;
        if (head2 != null && TWO_CHAR_SURNAME.containsKey(head2)) {
            surnames = TWO_CHAR_SURNAME.get(head2);
            rest = clean.substring(2);
        } else {
            surnames = SURNAME.getOrDefault(clean.charAt(0), syllable(clean.charAt(0)));
            rest = clean.substring(1);
        }

        Set<String> out = new LinkedHashSet<>();
        for (String sn : surnames) {
            for (String given : givenNames(rest)) {
                // 명단은 세 형태를 다 쓴다 — "Jong Un" · "Jong-Un" · 드물게 붙여 쓴 것.
                // 비교하는 쪽이 구두점을 지우므로 붙인 형태 하나면 셋을 다 덮는다.
                out.add((sn + " " + given).strip());
                // <b>성이 뒤에 오는 표기</b>도 만든다. 명단마다 순서가 다르다 —
                // 서양식으로 적힌 항목에 한국식 순서로만 대조하면 못 찾는다.
                out.add((given + " " + sn).strip());
                if (out.size() >= MAX) return out;
            }
        }
        return out;
    }

    /** 이름 부분. 음절마다 후보를 곱하되 상한에서 끊는다. */
    private static List<String> givenNames(String rest) {
        List<String> acc = new java.util.ArrayList<>(List.of(""));
        for (char c : rest.toCharArray()) {
            List<String> next = new java.util.ArrayList<>();
            for (String prefix : acc) {
                for (String s : syllable(c)) {
                    next.add(prefix.isEmpty() ? s : prefix + " " + s);
                    if (next.size() >= MAX) break;
                }
                if (next.size() >= MAX) break;
            }
            acc = next;
        }
        return acc;
    }

    /** 한 음절을 초성·중성·종성으로 갈라 표기 후보를 낸다. */
    private static List<String> syllable(char c) {
        if (c < BASE || c > LAST) return List.of(String.valueOf(c));
        int i = c - BASE;
        String[] on = ONSET[i / (21 * 28)];
        String[] nu = NUCLEUS[(i % (21 * 28)) / 28];
        String[] co = CODA[i % 28];
        List<String> out = new java.util.ArrayList<>();
        for (String o : on) for (String n : nu) for (String k : co) {
            out.add(o + n + k);
            if (out.size() >= 24) return out;   // 한 음절이 너무 많이 갈리지 않게
        }
        return out;
    }

    /**
     * 후보 중 하나가 <b>정확히</b> 맞는가.
     *
     * <h3>왜 점수가 아니라 정확 일치인가</h3>
     * 후보를 펼쳐 점수만 재 봤더니 김철수가 67 → 100점으로 올랐는데 <b>김철순도 92점</b>이 됐다.
     * 후보에 {@code KIM CHUL SOON} 이 생기고 그게 명단의 {@code KIM CHUL SOO} 와 한 글자 차이라
     * 같이 올라간 것이다. <b>후보를 늘린 만큼 남에게도 잘 걸린다.</b>
     *
     * <p>그래서 펼치기는 <b>점수를 올리는 장치가 아니라 정확 일치를 만드는 장치</b>로 쓴다.
     * 후보 중 하나가 명단 표기와 딱 맞으면 그건 우연이 아니고, 안 맞으면 원래 점수를 그대로 둔다 —
     * 이 저장소가 2차 식별자에서 <b>모르는 것은 점수를 안 건드린다</b>고 정한 것과 같은 규칙이다.
     *
     * @return 맞는 후보. 없으면 {@code null}
     */
    public static String exactMatch(String hangulName, String listedName) {
        if (listedName == null) return null;
        String target = normalize(listedName);
        if (target.isEmpty()) return null;
        for (String c : romanize(hangulName)) {
            if (normalize(c).equals(target)) return c;
        }
        return null;
    }

    /** 띄어쓰기·하이픈을 지우고 대문자로. 명단은 {@code RI JE-SON} 처럼 하이픈을 쓴다. */
    private static String normalize(String s) {
        return s == null ? "" : s.toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z]", "");
    }
}
