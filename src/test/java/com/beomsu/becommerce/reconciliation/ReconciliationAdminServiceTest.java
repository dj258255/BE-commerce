package com.beomsu.becommerce.reconciliation;

import com.beomsu.becommerce.reconciliation.internal.ReconciliationService;
import com.beomsu.becommerce.reconciliation.internal.ReconciliationResultRepository;
import com.beomsu.becommerce.reconciliation.internal.ReconciliationResult;
import com.beomsu.becommerce.reconciliation.internal.ReconciliationException;
import com.beomsu.becommerce.reconciliation.internal.ReconStatus;
import com.beomsu.becommerce.reconciliation.internal.ReconRunSummary;
import com.beomsu.becommerce.reconciliation.internal.ReconResultType;
import com.beomsu.becommerce.reconciliation.internal.ReconMismatchView;
import com.beomsu.becommerce.reconciliation.internal.PgSettlementCsvParser;
import com.beomsu.becommerce.reconciliation.internal.ExternalRecord;
import com.beomsu.becommerce.reconciliation.ResolveCause;
import com.beomsu.becommerce.reconciliation.cause.ClassifierAccuracyMetrics;
import com.beomsu.becommerce.reconciliation.cause.CauseClassifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ReconciliationAdminServiceTest {

    private ReconciliationResultRepository repository;
    private PgSettlementCsvParser parser;
    private ReconciliationService reconciliationService;
    private com.beomsu.becommerce.audit.AuditService auditService;
    private ReconciliationAdminService service;

    @BeforeEach
    void setUp() {
        repository = mock(ReconciliationResultRepository.class);
        parser = mock(PgSettlementCsvParser.class);
        reconciliationService = mock(ReconciliationService.class);
        auditService = mock(com.beomsu.becommerce.audit.AuditService.class);
        // 원인 분류기(ADR-012)는 이 테스트의 관심사가 아니라 목으로 둔다 — 분류 규칙은
        // CauseClassifierTest가 따로 검증한다.
        service = new ReconciliationAdminService(repository, auditService, parser, reconciliationService,
                mock(CauseClassifier.class), mock(ClassifierAccuracyMetrics.class),
                mock(org.springframework.context.ApplicationEventPublisher.class));
    }

    @Test
    @DisplayName("run: 파서로 외부 기록을 뽑아 대사 엔진에 위임하고 결과를 타입별로 집계한다")
    void runDelegatesToParserAndReconcileThenAggregates() {
        // 파서는 2건 파싱 + 1건 스킵을 보고한다.
        InputStream in = new ByteArrayInputStream("dummy".getBytes(StandardCharsets.UTF_8));
        when(parser.parse(any())).thenReturn(new PgSettlementCsvParser.ParseResult(
                List.of(ExternalRecord.of("ord-1", 10_000), ExternalRecord.of("ord-2", 20_000)), 1, java.util.List.of()));
        // 대사 엔진은 임의의 4분류 결과 목록을 돌려준다(엔진 로직은 여기서 검증 대상 아님).
        when(reconciliationService.reconcile(any(LocalDate.class), anyList())).thenReturn(List.of(
                ReconciliationResult.matched(LocalDate.of(2026, 7, 5), "ord-1", 10_000),
                ReconciliationResult.amountMismatch(LocalDate.of(2026, 7, 5), "ord-2", 20_000, 19_000),
                ReconciliationResult.internalOnly(LocalDate.of(2026, 7, 5), "ord-3", 5_000),
                ReconciliationResult.externalOnly(LocalDate.of(2026, 7, 5), "ord-4", 7_000)));

        ReconRunSummary summary = service.run(LocalDate.of(2026, 7, 5), in);

        assertThat(summary.external()).isEqualTo(2);   // 파싱된 외부 기록 수
        assertThat(summary.skipped()).isEqualTo(1);
        assertThat(summary.matched()).isEqualTo(1);
        assertThat(summary.internalOnly()).isEqualTo(1);
        assertThat(summary.externalOnly()).isEqualTo(1);
        assertThat(summary.amountMismatch()).isEqualTo(1);
        // pending = 사람 확인 필요 = internalOnly + externalOnly + amountMismatch
        assertThat(summary.pending()).isEqualTo(3);

        verify(parser).parse(in);
        verify(reconciliationService).reconcile(any(LocalDate.class), anyList());
    }

    @Test
    @DisplayName("listMismatches: PENDING 예외 큐만 조회해 페이지 뷰로 매핑한다")
    void listMismatchesMapsPendingToView() {
        ReconciliationResult mismatch = ReconciliationResult.amountMismatch(LocalDate.of(2026, 7, 5), "ord-1", 10_000, 9_000);
        Pageable pageable = PageRequest.of(0, 20);
        when(repository.findByStatus(eq(ReconStatus.PENDING), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(mismatch), pageable, 1));

        Page<ReconMismatchView> views = service.listMismatches(pageable);

        assertThat(views).hasSize(1);
        ReconMismatchView v = views.getContent().get(0);
        assertThat(v.orderNo()).isEqualTo("ord-1");
        assertThat(v.result()).isEqualTo(ReconResultType.AMOUNT_MISMATCH);
        assertThat(v.internalAmount()).isEqualTo(10_000);
        assertThat(v.externalAmount()).isEqualTo(9_000);
        // 조회는 PENDING 상태만 대상으로 한다(AUTO_RESOLVED는 제외).
        verify(repository).findByStatus(eq(ReconStatus.PENDING), any(Pageable.class));
        verify(repository, never()).findByStatus(eq(ReconStatus.AUTO_RESOLVED), any(Pageable.class));
    }

    @Test
    @DisplayName("resolve: PENDING을 MANUALLY_RESOLVED로 전이하고 saveAndFlush로 명시 영속한다")
    void resolveTransitionsAndPersists() {
        ReconciliationResult mismatch = ReconciliationResult.amountMismatch(LocalDate.of(2026, 7, 5), "ord-1", 10_000, 9_000);
        when(repository.findById(7L)).thenReturn(Optional.of(mismatch));
        when(repository.saveAndFlush(mismatch)).thenReturn(mismatch);

        ReconMismatchView view = service.resolve(7L, "admin", ResolveCause.PARTIAL_CANCEL_NOT_REFLECTED, "2차 취소 미반영");

        assertThat(mismatch.getStatus()).isEqualTo(ReconStatus.MANUALLY_RESOLVED);
        assertThat(view.orderNo()).isEqualTo("ord-1");
        // OSIV off — 상태 변경이 DB에 남으려면 saveAndFlush가 반드시 불려야 한다.
        verify(repository).saveAndFlush(mismatch);
        // 확정과 감사 기록은 같은 트랜잭션이다 — 확정만 되고 기록이 빠지는 상태를 만들지 않는다.
        verify(auditService).record(eq("admin"), eq("RECON_RESOLVE"),
                eq("RECONCILIATION_RESULT"), eq("7"), contains("PARTIAL_CANCEL_NOT_REFLECTED"));
    }

    @Test
    @DisplayName("resolve: 이미 확정된(PENDING 아님) 건은 예외 — saveAndFlush도 하지 않는다")
    void resolveRejectsNonPending() {
        ReconciliationResult matched = ReconciliationResult.matched(LocalDate.of(2026, 7, 5), "ord-2", 10_000); // AUTO_RESOLVED
        when(repository.findById(8L)).thenReturn(Optional.of(matched));

        assertThatThrownBy(() -> service.resolve(8L, "admin", ResolveCause.FEE_CALCULATION_DIFF, null))
                .isInstanceOf(ReconciliationException.class)
                .satisfies(e -> assertThat(((ReconciliationException) e).code())
                        .isEqualTo("INVALID_STATE_TRANSITION"));
        verify(repository, never()).saveAndFlush(any());
        verify(auditService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("resolve: 없는 id면 예외")
    void resolveThrowsWhenNotFound() {
        when(repository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolve(404L, "admin", ResolveCause.FEE_CALCULATION_DIFF, null))
                .isInstanceOf(ReconciliationException.class)
                .satisfies(e -> assertThat(((ReconciliationException) e).code())
                        .isEqualTo("RECON_RESULT_NOT_FOUND"));
        verify(repository, never()).saveAndFlush(any());
    }
}
