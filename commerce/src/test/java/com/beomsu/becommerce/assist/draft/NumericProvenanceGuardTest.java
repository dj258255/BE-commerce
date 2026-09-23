package com.beomsu.becommerce.assist.draft;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link NumericProvenanceGuard} 단위 테스트.
 *
 * <p><b>이 테스트가 모델보다 먼저 있어야 한다.</b> 모델 어댑터가 붙는 날 검사를
 * 새로 만들면, 그날 검사가 제대로 도는지 확인할 방법이 없다. 지어낸 값을 넣었을 때
 * 실제로 걸리는지를 지금 고정해 둔다.
 */
class NumericProvenanceGuardTest {

    private final NumericProvenanceGuard guard = new NumericProvenanceGuard();

    private FactPack facts() {
        return new FactPack("ORD-1",
                List.of("2026-08-30 · PAYMENT · 결제 승인"),
                Set.of(10_000L, 3_000L),
                Set.of(LocalDate.of(2026, 8, 30)),
                null, true);
    }

    @Test
    @DisplayName("출처에 있는 금액과 날짜만 쓴 초안은 통과한다")
    void passesGroundedDraft() {
        String draft = "2026-08-30에 10,000원이 승인되었고 3,000원이 취소되었습니다.";
        assertThat(guard.verify(draft, facts())).isEmpty();
    }

    @Test
    @DisplayName("출처에 없는 금액을 지어내면 걸린다")
    void catchesInventedAmount() {
        String draft = "2026-08-30에 10,000원이 승인되고 수수료 270원이 차감되었습니다.";
        assertThat(guard.verify(draft, facts()))
                .anySatisfy(s -> assertThat(s).contains("출처에 없는 금액").contains("270"));
    }

    @Test
    @DisplayName("출처에 없는 날짜를 지어내면 걸린다 — 표기 형식이 달라도")
    void catchesInventedDate() {
        assertThat(guard.verify("2026-08-25에 처리되었습니다.", facts()))
                .anySatisfy(s -> assertThat(s).contains("출처에 없는 날짜"));
        assertThat(guard.verify("2026년 8월 25일에 처리되었습니다.", facts()))
                .anySatisfy(s -> assertThat(s).contains("출처에 없는 날짜"));
    }

    @Test
    @DisplayName("천 단위 구분자 유무는 같은 값으로 본다 — 표기 차이로 반려하면 안 된다")
    void normalizesThousandsSeparator() {
        assertThat(guard.verify("10000원 승인", facts())).isEmpty();
        assertThat(guard.verify("10,000 원 승인", facts())).isEmpty();
    }

    @Test
    @DisplayName("금액이 아닌 맨 숫자는 통과시킨다 — 세는 말까지 막으면 정상 초안이 반려된다")
    void allowsBareCounts() {
        String draft = "항목 2개, 취소 1건이며 3일 이내에 환불됩니다. 총 10,000원입니다.";
        assertThat(guard.verify(draft, facts())).isEmpty();
    }

    @Test
    @DisplayName("존재하지 않는 날짜는 해석 불가로 잡는다 — 조용히 통과시키지 않는다")
    void catchesImpossibleDate() {
        assertThat(guard.verify("2026-02-30에 처리", facts()))
                .anySatisfy(s -> assertThat(s).contains("해석 불가"));
    }

    @Test
    @DisplayName("빈 초안은 검증할 게 없다 — 실패가 아니다")
    void emptyDraftIsNotAFailure() {
        assertThat(guard.verify(null, facts())).isEmpty();
        assertThat(guard.verify("   ", facts())).isEmpty();
    }
}
