package com.beomsu.becommerce.seller.screening;

import java.util.Locale;

/**
 * 이름을 <b>소리 나는 대로</b> 줄여 키를 만든다.
 *
 * <h3>왜 필요한가</h3>
 * 로마자 후보를 펼쳐 <b>정확히</b> 맞는 것만 보면, 후보에 없는 표기는 못 잡는다.
 * 같은 박씨가 {@code Park}·{@code Bak}·{@code Pak}·{@code Bahk} 를 쓰는데
 * <b>이건 오타가 아니라 다 맞는 표기</b>다. 후보를 아무리 늘려도 사람이 쓰는 것을 다 못 담는다.
 *
 * <p>업계는 이 자리를 <b>음성학 매칭</b>으로 메꾼다 — OFAC 자신의 검색 도구도
 * 편집거리(Jaro-Winkler)와 음성학(Soundex)을 <b>같이</b> 쓴다. 하나로 안 되니 병렬로 돌린다.
 *
 * <h3>Soundex 를 그대로 안 쓴 이유</h3>
 * Soundex 는 영어 이름을 겨냥해 만들어졌고 <b>첫 글자를 그대로 남긴다.</b>
 * 그런데 한국 이름에서 갈리는 자리가 정확히 첫 글자다 — {@code Park}/{@code Bak} 은
 * Soundex 로 {@code P620}/{@code B200} 이라 <b>아예 다른 키</b>가 된다.
 * 그래서 한국어 로마자에서 실제로 섞이는 짝(ㄱ↔ㅋ, ㅂ↔ㅍ, ㄷ↔ㅌ, ㅈ↔ㅊ, ㄹ↔ㄴ)을
 * 같은 소리로 접는 키를 따로 만든다.
 *
 * <h3>이 키만으로는 판정하지 않는다</h3>
 * 소리를 접을수록 <b>남남도 같은 키를 받는다.</b> 한국인의 50%가 성씨 다섯 개를 공유하는데,
 * 그 위에 소리까지 접으면 충돌이 더 는다. 그래서 이 키가 같다는 것은
 * <b>사람에게 보낼 이유</b>이지 일치의 근거가 아니다.
 */
public final class PhoneticKey {

    private PhoneticKey() {}

    /**
     * 소리 키. 만들 수 없으면 {@code null}.
     *
     * <p>{@code Park} · {@code Bak} · {@code Pak} · {@code Bahk} 가 모두 {@code PAK} 이 된다.
     */
    public static String of(String romanized) {
        if (romanized == null) return null;
        String s = romanized.toUpperCase(Locale.ROOT).replaceAll("[^A-Z]", "");
        if (s.isEmpty()) return null;

        // 이중자와 묵음을 먼저 정리한다. 순서가 중요하다 — CH 를 C·H 로 쪼개기 전에 잡아야 한다.
        s = s.replace("CK", "K").replace("CH", "C").replace("SH", "S").replace("PH", "P")
             .replace("TH", "T").replace("KH", "K").replace("GH", "G")
             .replace("WOO", "U").replace("YOO", "U")
             .replace("EE", "I").replace("OO", "U").replace("EO", "O")
             .replace("EU", "U").replace("AE", "E").replace("OE", "O");

        // <b>모음 뒤 R 은 버린다.</b> Park 의 R 은 소리가 아니라 영어식 표기다.
        // 이걸 ㄹ 로 읽어 접었더니 Park 이 PANK 가 되어 Bak 과 갈렸다.
        s = s.replaceAll("([AEIOU])R(?![AEIOU])", "$1");
        // 첫머리 Y 도 버린다 — Yi 와 Lee 는 같은 이(李)인데 Y 를 소리로 세면 갈린다.
        s = s.replaceFirst("^Y(?=[AEIOU])", "");

        var sb = new StringBuilder();
        char prev = 0;
        for (char c : s.toCharArray()) {
            char f = fold(c);
            if (f == 0) continue;          // 소리에 안 잡히는 글자(H, W 등)는 버린다
            if (f == prev) continue;       // 같은 소리가 이어지면 한 번만
            sb.append(f);
            prev = f;
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    /**
     * 한국어 로마자에서 <b>실제로 섞이는 짝</b>을 같은 소리로 접는다.
     * 영어 Soundex 와 다른 점은 <b>첫 글자도 접는다</b>는 것이다 — Park/Bak 이 갈리는 자리가 거기다.
     */
    private static char fold(char c) {
        return switch (c) {
            case 'G', 'K', 'Q' -> 'K';          // 김/Gim, 고/Ko
            case 'B', 'P', 'F', 'V' -> 'P';     // 박/Park·Bak, 백/Baek·Paek
            case 'D', 'T' -> 'T';               // 도/Do·To
            case 'J', 'C', 'Z' -> 'C';          // 정/Jung·Chung, 최/Choi·Choe
            case 'R', 'L' -> 'N';               // 이/Lee·Ri, 노/No·Ro — 초성 ㄹ·ㄴ 은 섞인다
            case 'N' -> 'N';
            case 'S', 'X' -> 'S';
            case 'M' -> 'M';
            case 'A', 'E', 'I', 'O', 'U', 'Y' -> 'A';   // 모음은 하나로 — 가장 많이 갈리는 자리다
            default -> 0;                        // H, W 는 버린다
        };
    }

    /** 두 이름이 같은 소리인가. 어느 쪽이든 키를 못 만들면 {@code false}. */
    public static boolean sounds(String a, String b) {
        String ka = of(a), kb = of(b);
        return ka != null && ka.equals(kb);
    }
}
