package com.beomsu.becommerce.home;

import java.util.List;

/**
 * 홈 페이지 — <b>행과 항목, 그리고 조립이 무엇을 했는지</b>.
 *
 * <p>계약 모양은 이미 목 fixture(`personalization/web/fixtures/homepage.json`)로 정해져 있었다
 * (`GET /api/v1/personalization/homepage`). 그 모양을 지킨다 — 화면과 스텁이 그 계약 위에 이미 서 있다.
 *
 * <p><b>{@code stats} 를 더한 이유</b>: 조립이 무엇을 <b>버렸는지</b>를 응답이 밝힌다. 중복·품절·미매칭을
 * 조용히 지우면 "왜 이 화면인가"를 아무도 복원할 수 없다 — 이 저장소가 반복해서 지켜 온 규칙이다
 * (폴백을 숨기지 않기, 창을 응답에 밝히기와 같은 이유). 화면은 이 필드를 안 써도 된다.
 *
 * <p>{@code page} 는 1부터이고, {@code nextCursor} 는 다음 쪽을 받을 때 그대로 돌려줄 값이다. 더 만들 행이
 * 없으면 {@code null} 이다(#237).
 */
public record HomePageView(String userId,
                           String generatedAt,
                           String source,
                           String fallbackReason,
                           Long contextStalenessMs,
                           Latency latency,
                           List<Row> rows,
                           AssemblyStats stats,
                           int page,
                           String nextCursor,
                           String experiment,
                           String variant) {

    /** 실험 표시 없이(#256 이전 모양). 2쪽부터는 추천 행이 없어 실험을 적지 않는다. */
    public HomePageView(String userId, String generatedAt, String source, String fallbackReason, Long contextStalenessMs,
                        Latency latency, List<Row> rows, AssemblyStats stats, int page, String nextCursor) {
        this(userId, generatedAt, source, fallbackReason, contextStalenessMs, latency, rows, stats, page, nextCursor, null, null);
    }

    /** 추천 결과가 어디서 왔는가. */
    public static final String SOURCE_MODEL = "MODEL";
    /** 추천을 못 받아 카탈로그만으로 조립했다 — 실패가 아니라 설계된 응답이다. */
    public static final String SOURCE_FALLBACK = "FALLBACK";

    /**
     * 요청 하나의 시간을 구간으로 나눈 것.
     *
     * <p><b>정직하게</b>: {@code constraintMs} 는 <b>추천 코어의 제약 확인</b>이고, 홈의 조립 비용은
     * {@code totalMs − (context+inference+constraint)} 로 남는다(E5 의 잔차와 같은 규칙).
     */
    public record Latency(long contextMs, long inferenceMs, long constraintMs, long totalMs) {
    }

    public record Row(String id, String title, String strategy, List<Item> items) {
    }

    /**
     * 상품 카드 하나.
     *
     * <p><b>{@code score} 가 null 인 이유</b>: 모델 스텁은 <b>순위만 내고 점수를 내지 않는다.</b>
     * 없는 점수를 만들어 넣으면 화면이 "관련도 0.91"처럼 보이지만 그 숫자의 근거가 없다 —
     * 모르는 것은 null 로 두고, 화면은 순위를 그대로 쓴다.
     *
     * <p><b>{@code imageUrl}·{@code inStock} 을 함께 내는 이유</b>: 소비자(스토어프론트·Next 앱)가
     * 카드를 그리려면 그 값이 필요하다. 없으면 소비자가 <b>상품 API를 한 번 더 불러야 하고</b>,
     * 그러면 홈이 조립한 것과 화면이 그리는 것이 갈라진다. 조립이 이미 그 값을 손에 쥐고 있으므로
     * (카탈로그 카드) 여기서 함께 내보낸다.
     *
     * <p><b>{@code category} 를 함께 내는 이유</b>: 조립의 <b>다양성 상한이 이 값으로 판단한다</b>.
     * 응답에 없으면 그 결정을 결과물만 보고 검증할 수 없다 — 항목이 빠진 이유가 상한인지 다른 것인지
     * 화면·응답 어디에도 안 남는다(실제로 #198 의 재측정에서 그 값이 필요했다).
     *
     * <p><b>{@code rank} 를 함께 내는 이유</b>: 조립이 <b>후보 리스트의 몇 번째를 꺼내 썼는가</b>다
     * (1부터). 되채우기가 칸을 더 깊은 후보로 사면 이 값이 내려간다 — 그래서 <b>되채우기의 대가를
     * 결과물에서 잴 수 있다</b>(#198①). 응답에 없으면 "더 나쁜 후보를 썼다"를 아무도 검증할 수 없다.
     */
    public record Item(String itemId, String name, long price, String imageUrl, boolean inStock,
                       Double score, String reason, String category, int rank) {
    }

    /**
     * 조립이 무엇을 했는가 — 후보 몇 개가 들어와 무엇 때문에 몇 개가 빠졌는가.
     *
     * <p>이 값이 있어야 "화면이 왜 이렇게 생겼는가"를 응답만 보고 복원할 수 있고, 실험도 이걸로 잰다.
     *
     * <p><b>{@code cappedOut} 이 있는 이유</b>: 다양성 상한에 걸려 빠진 항목을 <b>세지 않으면</b>
     * 그 손실이 어디에도 안 남는다 — 처음에 세지 않았고, 그래서 "규칙이 무엇을 하는가"를 표로
     * 설명할 수 없었다(항목 수가 줄어든 이유가 중복인지 상한인지 구분되지 않았다).
     */
    public record AssemblyStats(int candidates, int unmatched, int outOfStock, int duplicates,
                                int cappedOut, int distinctCategories) {
    }
}
