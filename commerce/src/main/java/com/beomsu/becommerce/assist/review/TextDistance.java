package com.beomsu.becommerce.assist.review;

/**
 * 두 글의 거리 — <b>초안을 얼마나 고쳐야 했나</b>를 숫자로 만든다.
 *
 * <p><b>왜 이 지표인가</b>: Klarna 가 AI 상담을 되돌린 원인은 기술이 아니라 지표 설계
 * 순서였다. 비용이 지배적인 평가 요소가 되면서 품질이 낮아졌다. 그래서 여기서는
 * <b>"상담원이 얼마나 고쳤나"를 먼저</b> 재고, 처리 시간·비용은 그다음이다.
 *
 * <p><b>문자 단위 편집 거리를 쓴다.</b> 한국어는 조사·어미가 붙어 어절 단위로 자르면
 * "확인됩니다"와 "확인되었습니다"가 완전히 다른 것으로 잡힌다. 실제로는 거의 안 고친 건데도
 * 크게 고친 것으로 나온다. 문자 단위가 그 차이를 작게 본다.
 *
 * <p><b>한계를 분명히 한다.</b> 편집 거리는 <b>표현이 얼마나 다른가</b>를 재지
 * <b>내용이 맞는가</b>를 재지 않는다. 완전히 틀린 초안을 통째로 다시 쓰면 거리가 크고,
 * 맞는 초안의 어미만 다듬어도 거리는 작다 — 여기까지가 이 숫자가 말할 수 있는 전부다.
 * "내용이 맞는가"는 사람이 판단할 몫으로 남는다.
 */
public final class TextDistance {

    private TextDistance() {
    }

    /**
     * 레벤슈타인 거리. 공백은 하나로 접어 비교한다 —
     * 줄바꿈이나 들여쓰기 차이를 "고쳤다"로 세면 안 된다.
     */
    public static int levenshtein(String a, String b) {
        String x = normalize(a);
        String y = normalize(b);
        if (x.isEmpty()) return y.length();
        if (y.isEmpty()) return x.length();

        // 한 줄만 들고 간다. 초안은 길어야 수백 자라 성능 문제는 없지만,
        // 표본이 쌓이면 집계에서 반복 호출되므로 굳이 전체 행렬을 잡지 않는다.
        int[] prev = new int[y.length() + 1];
        int[] cur = new int[y.length() + 1];
        for (int j = 0; j <= y.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= x.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= y.length(); j++) {
                int cost = x.charAt(i - 1) == y.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] swap = prev;
            prev = cur;
            cur = swap;
        }
        return prev[y.length()];
    }

    /**
     * 고친 정도를 0~1 로. <b>0이면 그대로 썼고 1이면 통째로 새로 썼다.</b>
     *
     * <p>긴 쪽으로 나눈다. 짧은 쪽으로 나누면 초안이 짧고 사람이 길게 쓴 경우
     * 1을 넘어 해석이 깨진다.
     */
    public static double editRatio(String from, String to) {
        String x = normalize(from);
        String y = normalize(to);
        int longer = Math.max(x.length(), y.length());
        if (longer == 0) {
            return 0.0;
        }
        return (double) levenshtein(x, y) / longer;
    }

    private static String normalize(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }
}
