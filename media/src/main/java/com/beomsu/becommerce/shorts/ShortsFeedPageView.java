package com.beomsu.becommerce.shorts;

import java.util.List;

/**
 * 숏폼 피드 한 쪽 응답(R26). {@code nextCursor}가 null이면 더 가져올 쪽이 없다(마지막 쪽).
 *
 * <p>{@code fallback}(R29) — 개인화 점수 계산이 실패했거나 제한 시간을 넘겨 <b>최신순 READY
 * 순서로 대신 돌려줬다</b>는 표시다. {@code items} 자체는 항상 READY만 담는다 — 폴백 여부와
 * 무관하다.
 */
public record ShortsFeedPageView(List<ShortsFeedItemView> items, Long nextCursor, boolean hasNext,
                                 boolean fallback) {
}
