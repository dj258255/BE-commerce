package com.beomsu.becommerce.reconciliation.internal;

import com.beomsu.becommerce.reconciliation.ResolveCause;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 대사 결과 — 내부/외부 기록을 매칭한 판정 1건.
 *
 * <p>4분류({@link ReconResultType})와 후속 상태({@link ReconStatus})를 남긴다. 일치 건은
 * 자동 종결(AUTO_RESOLVED), 불일치 건은 예외 큐(PENDING)로 사람 확인을 기다린다.
 * 정적 팩토리로만 만들어, 분류마다 어떤 금액 필드가 채워지는지를 강제한다.
 */
@Entity
@Table(name = "reconciliation_results",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_recon_result_date_order", columnNames = {"tradeDate", "orderNo"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReconciliationResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 대사 거래일. 같은 거래일의 같은 주문은 판정이 하나뿐이다(유니크).
     *
     * <p>이 유니크가 재실행을 멱등하게 만든다. 없으면 운영자가 같은 파일을 두 번 올릴 때
     * 예외 큐가 두 배가 되고, 미결건 게이지가 실제보다 부풀어 알림이 의미를 잃는다.
     */
    @Column(nullable = false)
    private LocalDate tradeDate;

    /** 외부에만 있는 경우에도 외부 orderNo를 기록하므로 사실상 채워지나, 스키마상 nullable 허용 */
    @Column(length = 64)
    private String orderNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ReconResultType result;

    /** 내부 기록 금액 — 외부에만 있으면 null */
    @Column
    private Long internalAmount;

    /** 외부 기록 금액 — 내부에만 있으면 null */
    @Column
    private Long externalAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReconStatus status;

    @Column(nullable = false)
    private Instant reconciledAt;

    /** 수기 확정한 운영자 — 자동 종결(AUTO_RESOLVED)이면 null */
    @Column(length = 100)
    private String resolvedBy;

    /** 확정 사유 코드 — 반복 패턴을 세기 위한 집계 축 (ADR-008) */
    @Enumerated(EnumType.STRING)
    @Column(length = 40)
    private ResolveCause resolveCause;

    /** 자유 서술 — {@link ResolveCause#OTHER}면 필수 */
    @Column(length = 500)
    private String resolveNote;

    @Column
    private Instant resolvedAt;

    private ReconciliationResult(LocalDate tradeDate, String orderNo, ReconResultType result,
                                 Long internalAmount, Long externalAmount, ReconStatus status) {
        this.tradeDate = tradeDate;
        this.orderNo = orderNo;
        this.result = result;
        this.internalAmount = internalAmount;
        this.externalAmount = externalAmount;
        this.status = status;
        this.reconciledAt = Instant.now();
    }

    /** 양쪽 일치 — 자동 종결. */
    public static ReconciliationResult matched(LocalDate tradeDate, String orderNo, long amount) {
        return new ReconciliationResult(tradeDate, orderNo, ReconResultType.MATCHED, amount, amount, ReconStatus.AUTO_RESOLVED);
    }

    /** 내부에만 있음(PG 누락 의심) — 사람 확인 필요. */
    public static ReconciliationResult internalOnly(LocalDate tradeDate, String orderNo, long internalAmount) {
        return new ReconciliationResult(tradeDate, orderNo, ReconResultType.INTERNAL_ONLY, internalAmount, null, ReconStatus.PENDING);
    }

    /** 외부에만 있음(내부 유실 의심) — 사람 확인 필요. */
    public static ReconciliationResult externalOnly(LocalDate tradeDate, String orderNo, long externalAmount) {
        return new ReconciliationResult(tradeDate, orderNo, ReconResultType.EXTERNAL_ONLY, null, externalAmount, ReconStatus.PENDING);
    }

    /** 양쪽에 있으나 금액 불일치 — 사람 확인 필요. */
    public static ReconciliationResult amountMismatch(LocalDate tradeDate, String orderNo, long internalAmount, long externalAmount) {
        return new ReconciliationResult(tradeDate, orderNo, ReconResultType.AMOUNT_MISMATCH, internalAmount, externalAmount, ReconStatus.PENDING);
    }

    /**
     * 수기 확정 — 사람이 예외 큐(PENDING)를 검토한 뒤 사유와 함께 종결한다. PENDING이 아니면 예외.
     *
     * <p><b>사유를 필수로 받는 이유</b>(ADR-008): 이전에는 상태만 전이하고 "누가/왜"는 감사 로그로
     * 남긴다고 했으나 그 배선이 없어 <b>조사 결과가 어디에도 기록되지 않았다.</b> 같은 불일치 패턴이
     * 다시 와도 매번 처음부터 조사해야 했다. 원인을 코드와 서술로 함께 받아 <b>반복 패턴을 셀 수 있게</b>
     * 한다. 자주 나오는 원인은 이후 규칙으로 자동 확정할 수 있다.
     *
     * <p>{@link ResolveCause#OTHER}는 서술을 반드시 요구한다. 목록에 없는 원인을 기존 코드에 억지로
     * 밀어 넣으면 집계가 오염되기 때문이다.
     */
    public void resolveManually(String actor, ResolveCause cause, String note) {
        if (status != ReconStatus.PENDING) {
            throw ReconciliationException.notPending(status);
        }
        if (actor == null || actor.isBlank()) {
            throw new ReconciliationException("RESOLVE_ACTOR_REQUIRED", "확정자는 필수입니다.");
        }
        if (cause == null) {
            throw new ReconciliationException("RESOLVE_CAUSE_REQUIRED", "확정 사유 코드는 필수입니다.");
        }
        if (cause == ResolveCause.OTHER && (note == null || note.isBlank())) {
            throw new ReconciliationException("RESOLVE_NOTE_REQUIRED",
                    "사유가 OTHER면 서술이 필수입니다.");
        }
        this.status = ReconStatus.MANUALLY_RESOLVED;
        this.resolvedBy = actor;
        this.resolveCause = cause;
        this.resolveNote = note;
        this.resolvedAt = Instant.now();
    }
}
