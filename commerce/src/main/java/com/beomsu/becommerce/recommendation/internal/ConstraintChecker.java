package com.beomsu.becommerce.recommendation.internal;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 제약 확인 — <b>언제</b> 확인하든 확인 자체는 여기 하나를 지난다({@link ConstraintPolicy}가 시점만 정한다).
 *
 * <p>하는 일은 단순하다: 후보 목록에서 <b>지금 팔 수 없는 것</b>을 빼고, 몇 개를 뺐는지 센다.
 * 복잡한 것은 <b>언제</b> 부르는 쪽에 있다.
 *
 * <p><b>스냅샷이 왜 값인가</b>: {@link #snapshot()}은 <b>풀 전체</b>를 읽는다. 모델 출력을 모르는
 * 시점에 확인하려면 후보가 될 수 있는 모든 것을 봐야 하기 때문이다. 실제 시스템에서 이건 곧
 * "캐시해 둔 가용성 맵"이고, 그래서 <b>갱신이 느리다</b> — 싼 대신 낡는다. 반대로
 * {@link #filterNow(List)}는 <b>출력만</b> 읽는다. 좁지만 요청마다 최신이다.
 *
 * <p>이 둘의 차이가 E4의 교환비다: <b>넓게 미리 읽으면 싸고 낡고, 좁게 나중에 읽으면 비싸고 최신이다.</b>
 */
@Component
public class ConstraintChecker {

    private final AvailabilitySource availability;

    public ConstraintChecker(AvailabilitySource availability) {
        this.availability = availability;
    }

    /**
     * 확인 시점의 사실 — <b>가용성 집합과 그때의 버전</b>.
     *
     * <p>버전을 함께 들고 다니는 이유: 나중에 "이 스냅샷을 뜬 뒤로 사실이 몇 번 바뀌었는가"를
     * 셀 수 있어야 한다. 창 안에서 아무것도 안 바뀌었는데 위반이 0이면 그건 확인 덕이 아니다.
     *
     * <p>{@code known}을 함께 담는 이유: 스냅샷을 뜬 뒤에 모델 출력을 받는데, 그 출력에
     * <b>풀에 없는 id</b>가 들어 있을 수 있다. 다시 원천을 묻지 않고 판정하려면 확인 대상이
     * 무엇이었는지도 스냅샷에 있어야 한다 — 스냅샷은 그때의 사실 전체다.
     */
    public record Snapshot(long version, Set<Long> known, Set<Long> unavailable, long takenAtNanos) {
    }

    /**
     * 모델을 부르기 전에 뜨는 넓은 스냅샷(풀 전체).
     *
     * <p><b>비용이 풀 크기에 비례한다.</b> 이것이 {@code AT_GENERATION_START} 의 정의이자 대가다 —
     * "미리 읽으면 싸다"는 풀이 작을 때의 말이고, 실제 카탈로그(10만 개)에서는 <b>풀 전체 조회</b>가
     * 요청마다 붙는다(M7 실측: 405ms). 그래서 이 정책의 기본값은 여전히 {@code AFTER_GENERATION} 이다.
     */
    public Snapshot snapshot() {
        List<Long> pool = availability.knownItemIds();
        return new Snapshot(availability.version(), Set.copyOf(pool),
                availability.unavailableAmong(pool), System.nanoTime());
    }

    /**
     * 지금 상태로 걸러낸다 — <b>출력만 읽는다.</b>
     *
     * <p><b>풀 전체를 읽지 않는 것이 중요하다(M7에서 드러났다).</b> 예전에는 {@link #snapshot()} 을
     * 불러 <b>후보 풀 전체</b>의 가용성을 읽었다. 실험용 풀(24개)에서는 0ms 라 안 보였지만,
     * 후보가 실제 카탈로그(10만 개)가 되자 <b>요청마다 10만 건짜리 조회</b>가 붙어 확인 비용이
     * 405ms 가 됐다 — 폴리시 비교가 아니라 <b>풀 크기</b>를 재고 있었다.
     *
     * <p>걸러내는 데 필요한 것은 <b>출력의 가용성</b>뿐이다. 모르는 id 는
     * {@link AvailabilitySource} 계약이 fail-closed(모르면 없다)라 그대로 걸러지므로,
     * "풀에 있었는가"를 따로 볼 필요도 없다 — 그 검사가 사라져도 계약은 유지된다.
     */
    public Filtered filterNow(List<Long> items) {
        long version = availability.version();
        Set<Long> unavailable = availability.unavailableAmong(items);
        Snapshot tight = new Snapshot(version, Set.copyOf(items), unavailable, System.nanoTime());
        return filter(items, tight);
    }

    /** 스냅샷으로 걸러낸다 — 스냅샷이 얼마나 낡았든 그대로 쓴다. 그게 {@code AT_GENERATION_START}의 정의다. */
    public Filtered filter(List<Long> items, Snapshot snapshot) {
        List<Long> kept = new ArrayList<>(items.size());
        int removed = 0;
        for (Long itemId : items) {
            // 모르는 id도 "팔 수 없다"로 본다 — 모르면 통과가 아니라 거절이다(AvailabilitySource 계약).
            if (snapshot.unavailable().contains(itemId) || !snapshot.known().contains(itemId)) {
                removed++;
            } else {
                kept.add(itemId);
            }
        }
        return new Filtered(List.copyOf(kept), removed, snapshot);
    }

    /** 걸러낸 결과 — 남은 목록·<b>뺀 개수</b>·판정에 쓴 스냅샷. */
    public record Filtered(List<Long> items, int removed, Snapshot snapshot) {
    }

    /** 확인에 쓴 스냅샷이 응답 시점에 얼마나 낡았는가(ms). */
    public long ageMs(Snapshot snapshot) {
        return (System.nanoTime() - snapshot.takenAtNanos()) / 1_000_000;
    }

    /** 스냅샷 이후 사실이 몇 번 바뀌었는가 — "창 안에서 바뀐 사실 수". */
    public long changesSince(Snapshot snapshot) {
        return availability.version() - snapshot.version();
    }

    /**
     * 응답에 실제로 나간 목록을 <b>독립적으로</b> 검사한다(실험 계기).
     *
     * <p>정책 로직이 아니라 바깥에서 다시 확인하는 이유: 정책이 "위반을 막았다"고 스스로 보고하면
     * 그건 검증이 아니라 주장이다. <b>최종 목록을 다시 사실과 대조</b>해야 위반율이 나온다.
     *
     * <p>이 호출은 정책과 무관하게 <b>모든 요청</b>에 붙으므로 정책 간 비교에서 상수다 — 다만
     * 절대 지연에는 더해지므로 응답이 <b>정책 경로 시간과 계기 시간을 나눠서</b> 보고한다.
     */
    /**
     * 후보 하나가 지금 팔 수 있는가 — <b>생성 중 차단</b>이 매 후보마다 부른다.
     *
     * <p>모르는 id는 {@link AvailabilitySource}의 규칙대로 <b>팔 수 없는 것</b>이다.
     * 여기서 반대로 하면 생성 중 차단만 관대해져 정책 간 비교가 깨진다.
     */
    public boolean isAvailable(long itemId) {
        return availability.unavailableAmong(List.of(itemId)).isEmpty();
    }

    public Set<Long> auditViolations(List<Long> finalItems) {
        return availability.unavailableAmong(finalItems);
    }
}
