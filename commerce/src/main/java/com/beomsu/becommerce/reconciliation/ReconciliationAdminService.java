package com.beomsu.becommerce.reconciliation;

import com.beomsu.becommerce.reconciliation.internal.ReconciliationService;
import com.beomsu.becommerce.reconciliation.internal.ReconciliationResultRepository;
import com.beomsu.becommerce.reconciliation.internal.ReconciliationResult;
import com.beomsu.becommerce.reconciliation.internal.ReconciliationException;
import com.beomsu.becommerce.reconciliation.internal.ReconStatus;
import com.beomsu.becommerce.reconciliation.internal.ReconRunSummary;
import com.beomsu.becommerce.reconciliation.internal.ReconMismatchView;
import com.beomsu.becommerce.reconciliation.internal.PgSettlementCsvParser;
import com.beomsu.becommerce.reconciliation.ResolveCause;
import com.beomsu.becommerce.reconciliation.cause.ClassifierAccuracyMetrics;
import com.beomsu.becommerce.reconciliation.CauseSuggestion;
import com.beomsu.becommerce.reconciliation.cause.CauseClassifier;
import com.beomsu.becommerce.reconciliation.internal.PgSettlementCsvParser.ParseResult;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import com.beomsu.becommerce.audit.AuditService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.List;

/**
 * 정산 대사 운영 어드민 — PG 정산 파일로 대사를 실행하고, 사람 확인이 필요한 불일치(예외 큐)를 조회·확정한다.
 *
 * <p>대사 엔진이 불일치 3분류(내부만/외부만/금액불일치)를 {@link ReconStatus#PENDING}으로 남기지만
 * (1) 외부 기록을 실제로 넣을 경로가 없었고 (2) 남은 예외 큐를 조회할 방법도 없었다. 이 어드민이
 * <b>업로드 → 대사 → 예외 큐 → 수기 확정</b> 루프를 닫는다.
 */
@lombok.extern.slf4j.Slf4j
@Service
@RequiredArgsConstructor
public class ReconciliationAdminService {

    private final ReconciliationResultRepository repository;
    private final AuditService auditService;
    private final PgSettlementCsvParser parser;
    private final ReconciliationService reconciliationService;
    private final CauseClassifier classifier;
    private final ClassifierAccuracyMetrics accuracyMetrics;
    private final ApplicationEventPublisher events;

    /**
     * PG 정산 파일(CSV)을 파싱해 대사 매칭 엔진을 돌리고 결과를 분류별로 집계한다.
     *
     * <p>파싱은 헤더 기반이라 컬럼 순서·부가 컬럼에 강하고, 불량/요약 행은 건너뛴다(스킵 수 집계).
     * <p>거래일을 받는 이유는 대사가 날짜 단위 작업이기 때문이다. PG 정산 파일은 하루치로 끊겨 오고,
     * 그 하루의 내부 기록과만 대조해야 한다. 전체와 비교하면 지난 날짜가 전부 불일치로 잡힌다.
     *
     * <p>매칭 엔진({@link ReconciliationService#reconcile})이 결과를 이미 영속하므로,
     * 여기서는 반환된 결과를 타입별로 세어 요약만 만든다. 불일치는 PENDING 예외 큐로 남아 수기 확정을 기다린다.
     */
    @Transactional
    public ReconRunSummary run(LocalDate tradeDate, InputStream file) {
        ParseResult parsed = parser.parse(file);
        List<ReconciliationResult> results =
                reconciliationService.reconcile(tradeDate, parsed.records());

        int matched = 0, internalOnly = 0, externalOnly = 0, amountMismatch = 0;
        for (ReconciliationResult r : results) {
            switch (r.getResult()) {
                case MATCHED -> matched++;
                case INTERNAL_ONLY -> internalOnly++;
                case EXTERNAL_ONLY -> externalOnly++;
                case AMOUNT_MISMATCH -> amountMismatch++;
            }
        }
        int pending = internalOnly + externalOnly + amountMismatch;

        // 금액이 걸린 채 건너뛴 행은 <조용히 넘길 수 없다>. 대사의 존재 이유가
        // "돈이 모르게 움직이지 않는다"인데, 파일에 금액이 있는데 매칭조차 안 된 행이다.
        var money = parsed.moneyBearing();
        if (!money.isEmpty()) {
            log.warn("정산 파일에 금액이 있는데 건너뛴 행 {}건 — 원본 확인 필요: {}",
                    money.size(), money);
        }
        return new ReconRunSummary(parsed.records().size(), parsed.skipped(), money.size(),
                reconciliationService.lastRunDuplicateRows(),
                matched, internalOnly, externalOnly, amountMismatch, pending);
    }

    /**
     * 이 대사 결과가 가리키는 주문번호.
     *
     * <p>확정 게이트가 <b>호출자가 준 주문번호를 믿지 않고</b> 여기서 다시 받으려고 열어 뒀다.
     * 화면이 다른 주문번호를 실어 보내면 엉뚱한 주문의 자료를 보고 확정하게 된다.
     */
    @Transactional(readOnly = true)
    public String orderNoOf(long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ReconciliationException("RECON_RESULT_NOT_FOUND",
                        "대사 결과를 찾을 수 없습니다: " + id))
                .getOrderNo();
    }

    /** 사람 확인이 필요한 불일치(PENDING) 페이지. */
    @Transactional(readOnly = true)
    public Page<ReconMismatchView> listMismatches(Pageable pageable) {
        return repository.findByStatus(ReconStatus.PENDING, pageable)
                .map(ReconciliationAdminService::toView);
    }

    /**
     * PENDING 대사 불일치 1건을 <b>사유와 함께</b> 수기 확정한다(사람 확인 후, ADR-008).
     *
     * <p>확정과 감사 기록을 <b>같은 트랜잭션</b>에 둔다. 나누면 확정은 됐는데 기록만 빠지는
     * 상태가 생기고, 그건 "누가 왜 확정했는지 모르는 종결"이라 감사 관점에서 최악이다.
     *
     * <p>상태 전이는 {@link ReconciliationResultRepository#saveAndFlush(Object)}로 <b>명시 영속</b>한다.
     * dirty-check 자동 flush는 <b>엔티티가 detached면 아예 동작하지 않아</b>(ReadOnlyFlushSemanticsTest ④)
     * 상태 확정을 명시적으로 강제한다. 예전에 "readOnly 조회가 끼면 MANUAL이 된다"고 적어뒀는데,
     * 재현해 보니 <b>바깥이 read-write면 참여한 안쪽 readOnly는 무시된다</b>(같은 테스트 ②). 그 설명은 틀렸다.
     */
    @Transactional
    public ReconMismatchView resolve(long id, String actor, ResolveCause cause, String note) {
        ReconciliationResult result = repository.findById(id)
                .orElseThrow(() -> new ReconciliationException("RECON_RESULT_NOT_FOUND",
                        "대사 결과를 찾을 수 없습니다: " + id));

        // 확정 <직전>에 분류기를 돌려 제안과 사람의 선택을 대조한다(ADR-012).
        // 사람의 확정이 곧 정답 라벨이므로, 이 시점이 유일하게 둘을 함께 아는 순간이다.
        // 실패해도 확정은 진행한다 — 지표 때문에 업무가 막히면 안 된다.
        try {
            accuracyMetrics.record(classifier.suggest(result), cause);
        } catch (RuntimeException e) {
            log.warn("분류기 일치율 집계 실패 id={}", id, e);
        }

        result.resolveManually(actor, cause, note);
        repository.saveAndFlush(result);

        // 감사 기록 — 같은 트랜잭션에 남긴다. 확정은 됐는데 기록만 빠지는 상태를 만들지 않는다.
        auditService.record(actor, "RECON_RESOLVE", "RECONCILIATION_RESULT", String.valueOf(id),
                "cause=" + cause + (note == null || note.isBlank() ? "" : " note=" + note));

        // 확정됐음을 알린다. 누가 듣는지는 모른다 — 섀도 초안 기록이 여기에 붙는다(ADR-014).
        // 커밋 뒤에 처리되므로 듣는 쪽이 느리거나 실패해도 확정은 이미 끝나 있다.
        events.publishEvent(new ReconciliationResolvedEvent(
                id, result.getOrderNo(), cause.name(), actor, java.time.Instant.now()));
        return toView(result);
    }

    private static ReconMismatchView toView(ReconciliationResult r) {
        return new ReconMismatchView(r.getId(), r.getOrderNo(), r.getResult(),
                r.getInternalAmount(), r.getExternalAmount(), r.getReconciledAt(),
                r.getResolvedBy(), r.getResolveCause(), r.getResolveNote(), r.getResolvedAt());
    }

    /**
     * 불일치 하나의 원인 후보를 제안한다 (ADR-012). <b>확정하지 않는다.</b>
     *
     * <p>목록 조회에 끼우지 않고 별도 호출로 둔 이유: 20건짜리 목록을 그릴 때마다
     * 20번 분류를 돌리면 결제 조회가 그만큼 늘어난다. 사람이 <b>한 건을 열어볼 때만</b> 필요하다.
     */
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public java.util.List<CauseSuggestion> suggestCauses(Long id) {
        ReconciliationResult result = repository.findById(id)
                .orElseThrow(() -> new ReconciliationException(
                        "RECON_RESULT_NOT_FOUND", "대사 결과를 찾을 수 없습니다: " + id));
        return classifier.suggest(result);
    }
}
