package com.beomsu.becommerce.dispute.internal;

import com.beomsu.becommerce.dispute.internal.DisputeStatus;
import com.beomsu.becommerce.dispute.internal.DisputeException;
import com.beomsu.becommerce.dispute.internal.Dispute;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DisputeTest {

    private Dispute openDispute() {
        return Dispute.open("cb-1", "order-1", 100L, 10_000, "fraudulent",
                Instant.now().plus(7, ChronoUnit.DAYS));
    }

    @Test
    @DisplayName("open: OPEN 상태로 개시된다")
    void opensInOpenState() {
        Dispute d = openDispute();
        assertThat(d.getStatus()).isEqualTo(DisputeStatus.OPEN);
        assertThat(d.getResolvedAt()).isNull();
        assertThat(d.getChargebackId()).isEqualTo("cb-1");
    }

    @Test
    @DisplayName("submitEvidence: OPEN → EVIDENCE_SUBMITTED, 메모 저장")
    void submitEvidenceTransitions() {
        Dispute d = openDispute();

        d.submitEvidence("영수증·배송 증적 첨부");

        assertThat(d.getStatus()).isEqualTo(DisputeStatus.EVIDENCE_SUBMITTED);
        assertThat(d.getEvidenceMemo()).isEqualTo("영수증·배송 증적 첨부");
    }

    @Test
    @DisplayName("submitEvidence: 이미 증빙 제출한 분쟁에 재제출하면 전이 예외")
    void submitEvidenceTwiceRejected() {
        Dispute d = openDispute();
        d.submitEvidence("1차");

        assertThatThrownBy(() -> d.submitEvidence("2차"))
                .isInstanceOf(DisputeException.class)
                .satisfies(e -> assertThat(((DisputeException) e).code()).isEqualTo("INVALID_DISPUTE_TRANSITION"));
    }

    @Test
    @DisplayName("resolve(true): OPEN → WON, resolvedAt 세팅")
    void resolveWonFromOpen() {
        Dispute d = openDispute();

        d.resolve(true);

        assertThat(d.getStatus()).isEqualTo(DisputeStatus.WON);
        assertThat(d.getResolvedAt()).isNotNull();
    }

    @Test
    @DisplayName("resolve(false): EVIDENCE_SUBMITTED → LOST, resolvedAt 세팅")
    void resolveLostFromEvidence() {
        Dispute d = openDispute();
        d.submitEvidence("증빙");

        d.resolve(false);

        assertThat(d.getStatus()).isEqualTo(DisputeStatus.LOST);
        assertThat(d.getResolvedAt()).isNotNull();
    }

    @Test
    @DisplayName("resolve: 이미 확정된(WON/LOST) 분쟁은 재확정 불가")
    void resolveAfterTerminalRejected() {
        Dispute d = openDispute();
        d.resolve(true); // WON

        assertThatThrownBy(() -> d.resolve(false))
                .isInstanceOf(DisputeException.class)
                .satisfies(e -> assertThat(((DisputeException) e).code()).isEqualTo("INVALID_DISPUTE_TRANSITION"));
    }

    @Test
    @DisplayName("submitEvidence: 이미 확정된 분쟁에는 증빙 제출 불가")
    void submitEvidenceAfterTerminalRejected() {
        Dispute d = openDispute();
        d.resolve(false); // LOST

        assertThatThrownBy(() -> d.submitEvidence("뒤늦은 증빙"))
                .isInstanceOf(DisputeException.class)
                .satisfies(e -> assertThat(((DisputeException) e).code()).isEqualTo("INVALID_DISPUTE_TRANSITION"));
    }
}
