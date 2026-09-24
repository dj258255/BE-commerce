package com.beomsu.becommerce.order.catalog.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 검색어를 엔진에 넘기기 전에 카탈로그의 말로 고친다(#260). 엔진과 무관하게 같은 규칙이다.
 *
 * <ul>
 *   <li><b>한국어</b>: 카탈로그 텍스트에는 한글이 없다(10만 건 중 3건). 그래서 형태소 분석으로는 맞을 수 없고 사전이 필요하다.
 *       사전은 앱에 이미 있는 한·영 라벨이다. 띄어쓰기를 무시하고 라벨을 가장 길게 맞춘다. 색상 라벨은 색상 필터로, 중분류 라벨은
 *       그 중분류의 원문 이름(영어)을 검색어로 바꾼다. 맞지 않는 한글 조각은 버린다</li>
 *   <li><b>미국식 → 영국식</b>: 카탈로그는 영국식이다. 미국식 말에 영국식 말을 <b>더한다</b>(원래 말은 둔다)</li>
 * </ul>
 */
public class QueryRewriter {

    /** 고친 결과. {@code colourCode} 는 한국어 색상 라벨에서 온 필터(없으면 null). */
    public record Rewritten(String text, String colourCode, List<String> matched, boolean changed) {
    }

    private static final Pattern HANGUL = Pattern.compile("[\\uAC00-\\uD7A3]");

    private final Map<String, String> colourByLabel;          // 공백 없앤 한국어 라벨 → 색상 코드
    private final Map<String, Set<String>> englishByLabel;    // 공백 없앤 한국어 라벨 → 원문 이름들
    private final Map<String, String> synonyms;               // 미국식(소문자) → 더할 영국식
    private final List<String> labelsLongestFirst;

    public QueryRewriter(Map<String, String> colourByKoreanLabel, Map<String, Set<String>> englishByKoreanLabel,
                         Map<String, String> usToUk) {
        this.colourByLabel = normalizeKeys(colourByKoreanLabel);
        this.englishByLabel = normalizeKeys(englishByKoreanLabel);
        this.synonyms = new LinkedHashMap<>();
        usToUk.entrySet().stream().sorted(Comparator.comparingInt((Map.Entry<String, String> e) -> -e.getKey().length()))
                .forEach(e -> synonyms.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue()));
        Set<String> labels = new LinkedHashSet<>(colourByLabel.keySet());
        labels.addAll(englishByLabel.keySet());
        this.labelsLongestFirst = labels.stream().sorted(Comparator.comparingInt(String::length).reversed()).toList();
    }

    /** 이 저장소가 쓰는 미국식 → 영국식 목록. 이슈 #260 에 측정 전에 고정했다. */
    public static Map<String, String> defaultSynonyms() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("pants", "trousers");
        m.put("tank top", "vest top");
        m.put("tank", "vest top");
        m.put("romper", "playsuit");
        m.put("overalls", "dungarees");
        m.put("headband", "alice band");
        m.put("pantyhose", "tights");
        m.put("bathing suit", "swimsuit");
        m.put("suspenders", "braces");
        return m;
    }

    public Rewritten rewrite(String query) {
        List<String> ascii = new ArrayList<>();
        StringBuilder hangul = new StringBuilder();
        for (String token : query.trim().split("\\s+")) {
            if (HANGUL.matcher(token).find()) {
                hangul.append(token.replaceAll("[^\\uAC00-\\uD7A3]", ""));
                String rest = token.replaceAll("[^A-Za-z0-9]", " ").trim().replaceAll("\\s+", " ");   // 한글 옆의 영어·숫자만
                if (!rest.isEmpty()) {
                    ascii.add(rest);
                }
            } else if (!token.isEmpty()) {
                ascii.add(token);
            }
        }
        List<String> matched = new ArrayList<>();
        Set<String> text = new LinkedHashSet<>(ascii);
        String colour = null;
        String h = hangul.toString();
        int i = 0;
        while (i < h.length()) {
            String hit = null;
            for (String label : labelsLongestFirst) {
                if (h.startsWith(label, i)) {
                    hit = label;
                    break;
                }
            }
            if (hit == null) {
                i++;                                   // 모르는 한글 한 자는 버린다(조사·접미사 등)
                continue;
            }
            matched.add(hit);
            if (colourByLabel.containsKey(hit) && colour == null) {
                colour = colourByLabel.get(hit);
            }
            if (englishByLabel.containsKey(hit)) {
                text.addAll(englishByLabel.get(hit));
            }
            i += hit.length();
        }
        String lower = String.join(" ", ascii).toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> s : synonyms.entrySet()) {
            if (Pattern.compile("\\b" + Pattern.quote(s.getKey()) + "\\b").matcher(lower).find()) {
                text.add(s.getValue());
                matched.add(s.getKey());
            }
        }
        String out = String.join(" ", text).trim();
        boolean changed = !matched.isEmpty() || !h.isEmpty();
        return new Rewritten(out, colour, List.copyOf(matched), changed);
    }

    /**
     * 라벨 키를 쿼리와 <b>같은 규칙</b>으로 다듬는다: 한글만 남긴다. 처음에는 공백만 지워서 "양말·타이츠"처럼 가운뎃점이 든 라벨이
     * 쿼리("양말타이츠"로 다듬어진다)와 절대 맞지 않았다(#260 첫 실측). 한글이 없는 라벨은 한국어 사전에 넣지 않는다.
     */
    private static <V> Map<String, V> normalizeKeys(Map<String, V> in) {
        Map<String, V> out = new LinkedHashMap<>();
        in.forEach((k, v) -> {
            String key = k.replaceAll("[^\\uAC00-\\uD7A3]", "");
            if (!key.isEmpty()) {
                out.putIfAbsent(key, v);
            }
        });
        return out;
    }
}
