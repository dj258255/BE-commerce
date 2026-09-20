package com.beomsu.becommerce.assist.draft;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("금액 결손 가드 — 부분 문자열로 열리지 않는다")
class AmountCoverageGuardTest {

    private final AmountCoverageGuard guard = new AmountCoverageGuard();

    private FactPack facts(long... amounts) {
        Set<Long> set = new java.util.LinkedHashSet<>();
        for (long a : amounts) {
            set.add(a);
        }
        return new FactPack("ORD-1", List.of("사실"), set, Set.of(LocalDate.of(2026, 6, 1)), null, true);
    }

    @Test
    @DisplayName("자리표가 있든 없든 그 값이면 담긴 것으로 본다")
    void acceptsBothForms() {
        assertThat(guard.missing("외부 17,300원", facts(17_300))).isEmpty();
        assertThat(guard.missing("외부 17300원", facts(17_300))).isEmpty();
    }

    @Test
    @DisplayName("더 큰 수 안에 우연히 들어 있는 것은 담긴 게 아니다")
    void rejectsSubstringOfLargerNumber() {
        // 173,000 이 있다고 17,300 이 담긴 것은 아니다
        assertThat(guard.missing("외부 173000원", facts(17_300))).containsExactly(17_300L);
        assertThat(guard.missing("외부 117,300원", facts(17_300))).containsExactly(17_300L);
        assertThat(guard.missing("외부 17,300,000원", facts(17_300))).containsExactly(17_300L);
    }

    @Test
    @DisplayName("자릿수 구분이 아닌 쉼표 뒤에서는 통과한다")
    void acceptsBeforeSentenceComma() {
        // 실제 서술에 나오는 모양이다: "외부 17,300, 내부 기록 없음"
        assertThat(guard.missing("대사 RECON_EXTERNAL_ONLY — 외부 17,300, 내부 기록 없음", facts(17_300)))
                .isEmpty();
    }

    @Test
    @DisplayName("빈 서술은 전부 결손이다")
    void blankMissesEverything() {
        assertThat(guard.missing("", facts(1_000, 2_000))).containsExactlyInAnyOrder(1_000L, 2_000L);
        assertThat(guard.missing(null, facts(1_000))).containsExactly(1_000L);
    }

    @Test
    @DisplayName("여러 금액 중 빠진 것만 집어낸다")
    void reportsOnlyMissing() {
        assertThat(guard.missing("내부 10,000 / 외부 기록 없음", facts(10_000, 9_700)))
                .containsExactly(9_700L);
    }
}
