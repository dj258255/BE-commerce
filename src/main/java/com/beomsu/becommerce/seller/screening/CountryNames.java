package com.beomsu.becommerce.seller.screening;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 나라를 두 글자 코드로 맞춘다.
 *
 * <p><b>왜 필요한가</b>: 우리는 판매자 국적을 {@code KR} 같은 두 글자로 들고 있는데,
 * <b>UN 명단은 {@code Democratic Republic of the Congo} 처럼 나라 이름으로 준다.</b>
 * 그대로 맞대면 <b>항상 불일치</b>가 되어 맞는 사람의 점수를 깎는다.
 * 실제 명단을 받아 보고서야 알았다 — 손으로 만든 XML 에는 {@code KP} 를 넣어 뒀었다.
 *
 * <p><b>못 알아보면 {@code null} 을 낸다.</b> 그러면 비교하는 쪽이 점수를 안 건드린다.
 * 억지로 맞추려 들면 틀린 나라로 잘못 붙어 <b>엉뚱한 사람을 끌어올린다.</b>
 */
public final class CountryNames {

    private static final Map<String, String> BY_NAME = new HashMap<>();

    static {
        // JDK 가 아는 나라 이름을 전부 넣는다. 손으로 적으면 빠뜨린다.
        for (String cc : Locale.getISOCountries()) {
            Locale l = Locale.of("", cc);
            BY_NAME.put(norm(l.getDisplayCountry(Locale.ENGLISH)), cc);
            BY_NAME.put(norm(l.getDisplayCountry(Locale.KOREAN)), cc);
        }
        // 명단이 쓰는 표기 중 JDK 이름과 다른 것들. 실제 파일에서 확인한 것만 넣는다.
        BY_NAME.put(norm("Democratic Republic of the Congo"), "CD");
        BY_NAME.put(norm("Democratic People's Republic of Korea"), "KP");
        BY_NAME.put(norm("Republic of Korea"), "KR");
        BY_NAME.put(norm("Islamic Republic of Iran"), "IR");
        BY_NAME.put(norm("Syrian Arab Republic"), "SY");
        BY_NAME.put(norm("Libyan Arab Jamahiriya"), "LY");
        BY_NAME.put(norm("United Republic of Tanzania"), "TZ");
        BY_NAME.put(norm("Russian Federation"), "RU");
        BY_NAME.put(norm("Viet Nam"), "VN");
        BY_NAME.put(norm("Cote d'Ivoire"), "CI");
        // 실제 명단에서 못 알아본 것들. 세어 보고 남은 것만 넣는다 — 짐작으로 넣으면 틀린 나라에 붙는다.
        BY_NAME.put(norm("Iran (Islamic Republic of)"), "IR");
        BY_NAME.put(norm("United States of America"), "US");
        BY_NAME.put(norm("United Kingdom of Great Britain and Northern Ireland"), "GB");
        BY_NAME.put(norm("Bosnia and Herzegovina"), "BA");
        BY_NAME.put(norm("State of Palestine"), "PS");
        BY_NAME.put(norm("Trinidad and Tobago"), "TT");
        BY_NAME.put(norm("Central African Republic"), "CF");
        BY_NAME.put(norm("United Arab Emirates"), "AE");
        BY_NAME.put(norm("Republic of Moldova"), "MD");
        BY_NAME.put(norm("Lao People's Democratic Republic"), "LA");
        BY_NAME.put(norm("Venezuela (Bolivarian Republic of)"), "VE");
        BY_NAME.put(norm("Bolivia (Plurinational State of)"), "BO");
        BY_NAME.put(norm("Saudi Arabia"), "SA");
        BY_NAME.put(norm("Sri Lanka"), "LK");
        BY_NAME.put(norm("Burkina Faso"), "BF");
        BY_NAME.put(norm("Sierra Leone"), "SL");
        BY_NAME.put(norm("South Sudan"), "SS");
        BY_NAME.put(norm("occupied Palestinian territory"), "PS");
    }

    private CountryNames() {}

    /** 두 글자 코드로 바꾼다. 이미 코드면 그대로, 못 알아보면 {@code null}. */
    public static String toCode(String value) {
        if (value == null || value.isBlank()) return null;
        String v = value.strip();
        if (v.length() == 2 && v.chars().allMatch(Character::isLetter)) {
            return v.toUpperCase(Locale.ROOT);
        }
        return BY_NAME.get(norm(v));
    }

    private static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9가-힣]", "");
    }
}
