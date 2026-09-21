package com.beomsu.becommerce.personalization.internal;

import java.time.Instant;
import java.util.List;

/**
 * 온라인 컨텍스트 — 저장소(Redis)에 들어가는 값이자 읽기 응답의 뼈대.
 *
 * <p>{@code seq}는 <b>지금까지 적용된 최대 순번</b>이다. 늦게 온 이벤트가 들어와도 줄어들지 않는다 —
 * "이 컨텍스트를 언제까지 아는가"의 기준이기 때문이다.
 *
 * <p>{@code updatedAt}은 <b>적용한 시각</b>(서버 시계)이다. 이벤트가 발생한 시각이 아니다 —
 * {@code stalenessMs}가 "이 컨텍스트를 언제 마지막으로 새로 알았는가"를 뜻해야 신선도를 잴 수 있다.
 *
 * <p><b>병합은 여기 없다.</b> {@code ContextStore}가 Lua 안에서 한다. 규칙은
 * "적용된 {@code seq} 집합에서 큰 것 {@code max-items}개"이고, 그래서 <b>도착 순서가 값을 바꾸지
 * 않는다</b>(ADR-035).
 */
public record OnlineContext(long seq, Instant updatedAt, List<Item> items) {

    /**
     * 컨텍스트에 담기는 활동 한 건.
     *
     * <p>{@code seq}를 <b>항목이 스스로 갖는다</b> — 그래야 늦게 온 이벤트를 제자리에 끼워 넣을 수
     * 있다(ADR-035). 이 필드가 없던 시절에는 항목의 나이를 알 수 없어 낮은 seq를 버릴 수밖에 없었다.
     */
    public record Item(long seq, long itemId, String activityType, Instant occurredAt) {
    }

    public static OnlineContext empty() {
        return new OnlineContext(0L, null, List.of());
    }

    /** 기대 순번까지 도달했는가 — 최신 반영률의 원천. */
    public boolean reached(long expectSeq) {
        return seq >= expectSeq;
    }
}
