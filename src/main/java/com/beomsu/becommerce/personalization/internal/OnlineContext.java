package com.beomsu.becommerce.personalization.internal;

import java.time.Instant;
import java.util.List;
import java.util.Map;

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
 *
 * <h2>{@code counts} — 창 집계를 목록 잘림에서 분리한다</h2>
 *
 * <p>{@code items}는 {@code max-items}로 잘린다. 그래서 <b>목록에서 세면 창 집계가 틀린다</b> —
 * E2가 "활동이 30건인데 목록은 20건이라 집계가 60%만 맞는다"를 관측했다. 그릇이 하나면 잘림과
 * 집계가 같이 망가진다.
 *
 * <p>{@code counts}는 <b>활동 유형별 수</b>를 따로 센다. 목록과 달리 잘리지 않으므로 창 집계가
 * 목록 크기에 의존하지 않는다. <b>창은 컨텍스트의 수명, 즉 TTL이다</b>(기본 7일) — 키가 만료되면
 * 창도 함께 초기화된다.
 *
 * <p>카운터는 <b>멱등하게</b> 증가한다 — 같은 {@code seq}는 병합 전에 걸러지므로 재배달이 두 번
 * 세어지지 않는다. 늦게 도착한 이벤트도 실제로 일어난 일이므로 한 번 세어진다. 목록과 집계가
 * {@code APPLY_LUA} 안에서 <b>같은 원자 단위</b>로 움직인다 — 따로 저장하면 어긋나는 순간이 생긴다.
 */
public record OnlineContext(long seq, Instant updatedAt, List<Item> items, Map<String, Long> counts) {

    /**
     * 컨텍스트에 담기는 활동 한 건.
     *
     * <p>{@code seq}를 <b>항목이 스스로 갖는다</b> — 그래야 늦게 온 이벤트를 제자리에 끼워 넣을 수
     * 있다(ADR-035). 이 필드가 없던 시절에는 항목의 나이를 알 수 없어 낮은 seq를 버릴 수밖에 없었다.
     */
    public record Item(long seq, long itemId, String activityType, Instant occurredAt) {
    }

    /**
     * {@code counts}가 없던 판으로 저장된 값에는 이 필드가 없다 — Jackson이 {@code null}을 넣으므로
     * 여기서 정규화한다. 목록도 같은 이유로 방어한다.
     */
    public OnlineContext {
        items = items == null ? List.of() : List.copyOf(items);
        counts = counts == null ? Map.of() : Map.copyOf(counts);
    }

    public static OnlineContext empty() {
        return new OnlineContext(0L, null, List.of(), Map.of());
    }

    /** 창 안의 총 활동 수 — 유형별 카운터의 합. 목록 크기와 다르다(잘리지 않는다). */
    public long totalActivities() {
        return counts.values().stream().mapToLong(Long::longValue).sum();
    }

    /** 기대 순번까지 도달했는가 — 최신 반영률의 원천. */
    public boolean reached(long expectSeq) {
        return seq >= expectSeq;
    }
}
