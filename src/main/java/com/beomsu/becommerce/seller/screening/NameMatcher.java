package com.beomsu.becommerce.seller.screening;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.Locale;

/**
 * 이름을 명단 항목과 대조해 0~100 점을 낸다. <b>오탐이 나오는 자리가 여기다.</b>
 *
 * <p><b>왜 정확 일치로 안 되나</b>: 같은 사람·법인이 표기가 달라 들어온다. 대소문자,
 * 공백, 법인격 표기(주식회사·(주)·Co., Ltd.), 로마자 전사 방식이 제각각이다. 정확 일치만 쓰면
 * <b>놓치고</b>, 아무 유사도나 쓰면 <b>흔한 이름이 전부 걸린다.</b>
 *
 * <p><b>단계를 나눈 이유</b>: 정규화로 없앨 수 있는 차이를 유사도에 맡기면, 유사도 임계를
 * 낮춰야 하고 그만큼 오탐이 는다. 먼저 지울 수 있는 것을 지우고, 남은 차이만 유사도로 본다.
 * 이 프로젝트가 대사 원인을 산수로 먼저 가른 것과 같은 순서다.
 */
@Component
public class NameMatcher {

    /** 법인격 표기. 있고 없고가 같은 법인을 다르게 만든다. */
    private static final String[] LEGAL_FORMS = {
            "주식회사", "(주)", "㈜", "유한회사", "합자회사",
            "co., ltd.", "co.,ltd.", "co ltd", "ltd.", "ltd", "inc.", "inc", "llc", "corp.", "corp"
    };

    /**
     * 표기 차이를 지운다. 유사도에 맡기기 전에 지울 수 있는 것을 먼저 지운다.
     *
     * <p>유니코드 정규화(NFKC)를 먼저 거는 이유는 전각·반각과 합자 문자가 같은 글자로
     * 모이게 하기 위해서다. 이걸 안 하면 눈에 같아 보이는 이름이 다른 문자열이 된다.
     */
    String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String s = Normalizer.normalize(raw, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        for (String form : LEGAL_FORMS) {
            s = s.replace(form, " ");
        }
        // 구두점을 두 갈래로 나눈다. 처음에 전부 공백으로 바꿨더니 <b>O'Brien 이 O Brien 이 되어</b>
        // 붙어 있던 이름이 갈라졌다(테스트가 잡았다). 이름 안에 들어가는 아포스트로피는 지우고,
        // 이름을 나누는 하이픈·쉼표·마침표만 공백으로 바꾼다.
        s = s.replaceAll("['\u2019\u02BC`]", "");
        s = s.replaceAll("[\\p{Punct}]", " ").replaceAll("\\s+", " ").strip();
        return s;
    }

    /**
     * 0~100. 100 이면 정규화 후 완전히 같다.
     *
     * <p>편집 거리를 길이로 나눠 비율로 만든다. 절대 거리를 쓰면 <b>긴 이름이 유리해진다</b> —
     * 두 글자 다른 것이 세 글자 이름에서는 큰 차이인데 스무 글자 이름에서는 아니다.
     */
    public int score(String a, String b) {
        String x = normalize(a);
        String y = normalize(b);
        if (x.isEmpty() || y.isEmpty()) {
            return 0;
        }
        if (x.equals(y)) {
            return 100;
        }
        int distance = levenshtein(x, y);
        int longer = Math.max(x.length(), y.length());
        return (int) Math.round((1.0 - (double) distance / longer) * 100);
    }

    private int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        return prev[b.length()];
    }
}
