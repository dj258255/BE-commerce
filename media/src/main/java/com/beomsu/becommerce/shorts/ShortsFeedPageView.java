package com.beomsu.becommerce.shorts;

import java.util.List;

/** 숏폼 피드 한 쪽 응답(R26). {@code nextCursor}가 null이면 더 가져올 쪽이 없다(마지막 쪽). */
public record ShortsFeedPageView(List<ShortsFeedItemView> items, Long nextCursor, boolean hasNext) {
}
