package com.beomsu.becommerce.personalization.internal;

import com.beomsu.becommerce.personalization.UserActivityEvent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 온라인 컨텍스트 — 저장소(Redis)에 들어가는 값이자 읽기 응답의 뼈대.
 *
 * <p>{@code seq}가 이 값의 <b>유일한 순서 판정 기준</b>이다. 들어온 이벤트의 seq가 이 값보다 크지
 * 않으면 아무것도 하지 않는다 — at-least-once 재배달과 파티션 내 순서 역전이 같은 규칙 하나로 막힌다.
 *
 * <p>{@code updatedAt}은 <b>적용한 시각</b>(서버 시계)이다. 이벤트가 발생한 시각이 아니다 —
 * {@code stalenessMs}가 "이 컨텍스트를 언제 마지막으로 새로 알았는가"를 뜻해야 신선도를 잴 수 있다.
 */
public record OnlineContext(long seq, Instant updatedAt, List<Item> items) {

    /** 컨텍스트에 담기는 최근 활동 한 건. */
    public record Item(long itemId, String activityType, Instant occurredAt) {
    }

    public static OnlineContext empty() {
        return new OnlineContext(0L, null, List.of());
    }

    /** 이 이벤트를 반영한 새 컨텍스트. 최근 것부터 앞에 쌓고 {@code maxItems}로 자른다. */
    public OnlineContext applied(UserActivityEvent event, int maxItems) {
        List<Item> merged = new ArrayList<>(items.size() + 1);
        merged.add(new Item(event.itemId(), event.activityType(), event.occurredAt()));
        merged.addAll(items);
        int keep = Math.min(merged.size(), Math.max(maxItems, 1));
        return new OnlineContext(event.seq(), Instant.now(), List.copyOf(merged.subList(0, keep)));
    }

    /** 기대 순번까지 도달했는가 — 최신 반영률의 원천. */
    public boolean reached(long expectSeq) {
        return seq >= expectSeq;
    }
}
