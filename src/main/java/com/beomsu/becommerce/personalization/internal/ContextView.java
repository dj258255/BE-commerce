package com.beomsu.becommerce.personalization.internal;

import java.time.Instant;
import java.util.List;

/**
 * 온라인 읽기 응답 — 컨텍스트와 <b>그 컨텍스트가 얼마나 최신인지</b>를 함께 돌려준다.
 *
 * <p>{@code reflected}가 E1의 "최신 반영률" 원천이다: 호출자가 {@code expectSeq}로 "내가 낸 이벤트"를
 * 지정하면, 그 순번까지 컨텍스트에 도달했는지를 서버가 판정해 준다. 클라이언트가 시각을 비교하는
 * 방식보다 정확하다 — 서버 간 시계 차이에 기대지 않기 때문이다.
 *
 * <p>{@code source}로 <b>폴백을 숨기지 않는다.</b> {@code EMPTY}면 저장소 장애이거나 활동이 아직
 * 없는 것이다. 화면이 "폴백으로 응답했으면 폴백이라고 보여준다"는 규칙
 * ({@code personalization/web/README.md})의 서버 쪽 짝이다.
 */
public record ContextView(long userId,
                          long seq,
                          boolean reflected,
                          Long stalenessMs,
                          long waitedMs,
                          int itemCount,
                          List<OnlineContext.Item> items,
                          String source) {

    /** 저장소에서 읽었다. */
    public static final String SOURCE_CONTEXT = "CONTEXT";
    /** 읽지 못했거나 아직 아무것도 없다 — 개인화 없이 응답한 상태다. */
    public static final String SOURCE_EMPTY = "EMPTY";

    public static ContextView of(long userId, OnlineContext context, boolean reflected,
                                 long waitedMs, String source) {
        return new ContextView(userId, context.seq(), reflected,
                context.updatedAt() == null ? null : Math.max(0, Instant.now().toEpochMilli() - context.updatedAt().toEpochMilli()),
                waitedMs, context.items().size(), context.items(), source);
    }

    public static ContextView empty(long userId, long waitedMs) {
        return of(userId, OnlineContext.empty(), false, waitedMs, SOURCE_EMPTY);
    }
}
