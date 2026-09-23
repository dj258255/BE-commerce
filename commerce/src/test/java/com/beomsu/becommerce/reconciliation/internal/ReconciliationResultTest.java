package com.beomsu.becommerce.reconciliation.internal;

import com.beomsu.becommerce.reconciliation.internal.ReconciliationResult;
import com.beomsu.becommerce.reconciliation.internal.ReconciliationException;
import com.beomsu.becommerce.reconciliation.internal.ReconStatus;
import com.beomsu.becommerce.reconciliation.ResolveCause;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReconciliationResultTest {

    @Test
    @DisplayName("resolveManually: PENDING 불일치를 MANUALLY_RESOLVED로 확정한다")
    void resolveManuallyFromPending() {
        ReconciliationResult r = ReconciliationResult.internalOnly(LocalDate.of(2026, 7, 5), "ord-1", 10_000);
        assertThat(r.getStatus()).isEqualTo(ReconStatus.PENDING);

        r.resolveManually("admin", ResolveCause.PG_FILE_DELAY, "다음 회차 파일에 포함 예정");

        assertThat(r.getStatus()).isEqualTo(ReconStatus.MANUALLY_RESOLVED);
        assertThat(r.getResolvedBy()).isEqualTo("admin");
        assertThat(r.getResolveCause()).isEqualTo(ResolveCause.PG_FILE_DELAY);
        assertThat(r.getResolvedAt()).isNotNull();
    }

    @Test
    @DisplayName("resolveManually: 자동 종결(AUTO_RESOLVED) 건은 수기 확정할 수 없다")
    void resolveManuallyRejectsAutoResolved() {
        ReconciliationResult matched = ReconciliationResult.matched(LocalDate.of(2026, 7, 5), "ord-1", 10_000);

        assertThatThrownBy(() -> matched.resolveManually("admin", ResolveCause.PG_FILE_DELAY, null))
                .isInstanceOf(ReconciliationException.class)
                .satisfies(e -> assertThat(((ReconciliationException) e).code())
                        .isEqualTo("INVALID_STATE_TRANSITION"));
    }

    @Test
    @DisplayName("resolveManually: 이미 수기 확정된 건을 다시 확정하면 예외(멱등 아님 — 상태 가드)")
    void resolveManuallyRejectsAlreadyResolved() {
        ReconciliationResult r = ReconciliationResult.amountMismatch(LocalDate.of(2026, 7, 5), "ord-1", 10_000, 9_000);
        r.resolveManually("admin", ResolveCause.FEE_CALCULATION_DIFF, null);

        assertThatThrownBy(() -> r.resolveManually("admin", ResolveCause.FEE_CALCULATION_DIFF, null))
                .isInstanceOf(ReconciliationException.class);
    }

    @Test
    @DisplayName("resolveManually: 사유 코드가 없으면 확정할 수 없다 — 사유 없는 종결을 막는다")
    void resolveManuallyRequiresCause() {
        ReconciliationResult r = ReconciliationResult.internalOnly(LocalDate.of(2026, 7, 5), "ord-1", 10_000);

        assertThatThrownBy(() -> r.resolveManually("admin", null, "메모"))
                .isInstanceOf(ReconciliationException.class)
                .satisfies(e -> assertThat(((ReconciliationException) e).code())
                        .isEqualTo("RESOLVE_CAUSE_REQUIRED"));
        assertThat(r.getStatus()).isEqualTo(ReconStatus.PENDING);
    }

    @Test
    @DisplayName("resolveManually: OTHER는 서술이 필수 — 목록에 없는 원인을 기존 코드로 뭉개지 않게 한다")
    void resolveManuallyRequiresNoteWhenOther() {
        ReconciliationResult r = ReconciliationResult.internalOnly(LocalDate.of(2026, 7, 5), "ord-1", 10_000);

        assertThatThrownBy(() -> r.resolveManually("admin", ResolveCause.OTHER, "  "))
                .isInstanceOf(ReconciliationException.class)
                .satisfies(e -> assertThat(((ReconciliationException) e).code())
                        .isEqualTo("RESOLVE_NOTE_REQUIRED"));
        assertThat(r.getStatus()).isEqualTo(ReconStatus.PENDING);
    }

    @Test
    @DisplayName("resolveManually: 확정자가 비어 있으면 예외 — 누가 확정했는지 없는 종결을 막는다")
    void resolveManuallyRequiresActor() {
        ReconciliationResult r = ReconciliationResult.internalOnly(LocalDate.of(2026, 7, 5), "ord-1", 10_000);

        assertThatThrownBy(() -> r.resolveManually(" ", ResolveCause.PG_FILE_DELAY, null))
                .isInstanceOf(ReconciliationException.class)
                .satisfies(e -> assertThat(((ReconciliationException) e).code())
                        .isEqualTo("RESOLVE_ACTOR_REQUIRED"));
    }
}
