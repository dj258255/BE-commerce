package com.beomsu.becommerce.personalization.internal;

import java.time.Instant;
import java.util.List;

/**
 * 온라인 컨텍스트 — 저장소(Redis)에 들어가는 값이자 읽기 응답의 뼈대.
 *
 * <p>{@code seq}가 이 값의 <b>유일한 순서 판정 기준</b>이다. 들어온 이벤트의 seq가 이 값보다 크지
 * 않으면 아무것도 하지 않는다 — at-least-once 재배달과 파티션 내 순서 역전이 같은 규칙 하나로 막힌다.
 *
 * <p>{@code updatedAt}은 <b>적용한 시각</b>(서버 시계)이다. 이벤트가 발생한 시각이 아니다 —
 * {@code stalenessMs}가 "이 컨텍스트를 언제 마지막으로 새로 알았는가"를 뜻해야 신선도를 잴 수 있다.
 *
 * <p><b>병합은 여기 없다.</b> 예전에는 {@code applied(...)}가 Java에서 목록을 합쳤는데, 그러면
 * 읽기와 쓰기 사이에 다른 스레드가 끼어들어 항목을 잃었다(E2가 관측). 지금은 {@code ContextStore}가
 * Lua 안에서 병합한다 — 이 record는 값의 모양만 정의한다.
 */
public record OnlineContext(long seq, Instant updatedAt, List<Item> items) {

    /** 컨텍스트에 담기는 최근 활동 한 건. */
    public record Item(long itemId, String activityType, Instant occurredAt) {
    }

    public static OnlineContext empty() {
        return new OnlineContext(0L, null, List.of());
    }

    /** 기대 순번까지 도달했는가 — 최신 반영률의 원천. */
    public boolean reached(long expectSeq) {
        return seq >= expectSeq;
    }
}
