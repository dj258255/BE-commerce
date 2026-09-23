package com.beomsu.becommerce.assist.resolve;

import com.beomsu.becommerce.reconciliation.ReconciliationAdminService;
import com.beomsu.becommerce.reconciliation.ResolveCause;
import com.beomsu.becommerce.timeline.OrderTimeline;
import com.beomsu.becommerce.timeline.OrderTimelineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * <b>자료가 빠진 채로 확정하는 것을 서버가 막는지</b> 본다.
 *
 * <p>여태 화면만 경고했다. 경고는 API 를 직접 부르면 그만이고, 급한 운영자는 배너를 지나친다.
 * 이 절이 잡으려던 문제가 "운영자가 기록이 없다로 읽으면 틀린 판정이 장부에 남는다"인데,
 * 표시까지만 하고 닫으면 절반만 한 것이다.
 */
class GuardedResolveServiceTest {

    private OrderTimelineService timelineService;
    private ReconciliationAdminService reconciliation;
    private GuardedResolveService service;

    @BeforeEach
    void setUp() {
        timelineService = mock(OrderTimelineService.class);
        reconciliation = mock(ReconciliationAdminService.class);
        service = new GuardedResolveService(timelineService, reconciliation);
    }

    private void timelineWith(List<String> unavailable) {
        // 주문번호는 <서버가 대사 결과에서> 꺼낸다. 호출자가 준 값을 쓰지 않는다.
        when(reconciliation.orderNoOf(1L)).thenReturn("ORD-1");
        when(timelineService.assemble("ORD-1"))
                .thenReturn(new OrderTimeline("ORD-1", List.of(), unavailable));
    }

    @Test
    @DisplayName("주문번호는 호출자가 준 것을 안 쓰고 대사 결과에서 직접 꺼낸다")
    void orderNoComesFromTheReconResultNotTheCaller() {
        timelineWith(List.of());

        service.resolve(1L, "admin", ResolveCause.PG_FILE_DELAY, null, List.of());

        // 엉뚱한 주문번호를 실어 보내도 서버가 본 것은 대사 결과가 가리키는 주문이다
        verify(reconciliation).orderNoOf(1L);
        verify(timelineService).assemble("ORD-1");
    }

    @Test
    @DisplayName("자료가 온전하면 그대로 확정한다")
    void passesWhenComplete() {
        timelineWith(List.of());

        service.resolve(1L, "admin", ResolveCause.PG_FILE_DELAY, "메모", List.of());

        verify(reconciliation).resolve(1L, "admin", ResolveCause.PG_FILE_DELAY, "메모");
    }

    @Test
    @DisplayName("자료가 빠졌는데 확인 표시가 없으면 막는다 — 화면 경고만으로는 우회된다")
    void blocksWhenEvidenceMissingAndNotAcknowledged() {
        timelineWith(List.of("LEDGER", "SETTLEMENT"));

        assertThatThrownBy(() ->
                service.resolve(1L, "admin", ResolveCause.PG_FILE_DELAY, "메모", List.of()))
                .isInstanceOf(IncompleteEvidenceException.class)
                .extracting(e -> ((IncompleteEvidenceException) e).code())
                .isEqualTo("RESOLVE_EVIDENCE_INCOMPLETE");

        verify(reconciliation, never()).resolve(anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("빠진 목록과 다르게 적어 내면 막는다 — 화면이 본 것과 지금이 다르다는 뜻이다")
    void blocksWhenAcknowledgedListDiffers() {
        timelineWith(List.of("LEDGER", "SETTLEMENT"));

        assertThatThrownBy(() ->
                service.resolve(1L, "admin", ResolveCause.PG_FILE_DELAY, null, List.of("LEDGER")))
                .isInstanceOf(IncompleteEvidenceException.class);

        verify(reconciliation, never()).resolve(anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("알고 넘어가면 통과시키되 그 사실을 확정 사유에 남긴다")
    void passesWhenAcknowledgedButRecordsIt() {
        timelineWith(List.of("LEDGER", "SETTLEMENT"));

        service.resolve(1L, "admin", ResolveCause.PG_FILE_DELAY, "메모",
                List.of("SETTLEMENT", "LEDGER"));

        var note = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(reconciliation).resolve(eq(1L), eq("admin"), eq(ResolveCause.PG_FILE_DELAY), note.capture());
        assertThat(note.getValue())
                .as("나중에 이 확정이 무엇을 못 보고 내려진 것인지 답할 수 있어야 한다")
                .contains("자료 없이 확정").contains("LEDGER").contains("SETTLEMENT");
    }
}
