package com.beomsu.becommerce.assist.resolve;

import com.beomsu.becommerce.reconciliation.ReconciliationAdminService;
import com.beomsu.becommerce.reconciliation.ResolveCause;
import com.beomsu.becommerce.timeline.OrderTimeline;
import com.beomsu.becommerce.timeline.OrderTimelineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * <b>자료가 빠진 채로 확정하는 것을 서버에서 막는다.</b>
 *
 * <p>여태 화면만 경고했다. 경고는 우회된다 — API 를 직접 부르면 그만이고, 급한 운영자는
 * 노란 배너를 지나친다. <b>운영자가 "기록이 없다"로 읽으면 틀린 판정이 장부에 남는데</b>,
 * 그게 이 절이 처음에 잡으려던 바로 그 문제다. 표시까지만 하고 닫으면 절반만 한 것이다.
 *
 * <p><b>왜 여기 있나</b>: 대사 모듈이 타임라인을 직접 부르면 순환이다
 * ({@code timeline → reconciliation} 이 이미 있다). 그래서 <b>둘 다에 의존해도 되는 제3의
 * 모듈</b>에 확정 게이트를 둔다. {@code assist} 는 이미 타임라인과 대사를 함께 읽고 있어
 * 새 모듈을 만들지 않고 여기 붙인다.
 *
 * <p><b>막지 않고 통과시키는 길도 남긴다.</b> 자료가 영영 안 올 수도 있고 그때도 장부는
 * 닫아야 한다. 대신 <b>모르고 지나치는 것과 알고 넘어가는 것을 가른다</b> — 넘어가려면
 * 빠진 출처를 호출자가 그대로 적어 내야 하고, 그 사실이 확정 사유에 남는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GuardedResolveService {

    private final OrderTimelineService timelineService;
    private final ReconciliationAdminService reconciliation;

    /**
     * 확정한다. 자료가 빠져 있으면 {@code acknowledgedMissing} 이 그 목록과 <b>정확히 같을 때만</b>
     * 통과시킨다.
     *
     * <p><b>서버가 조회한 누락 목록과 운영자가 확인한 목록이 같을 때만</b> 통과시킨다. 호출자가
     * 보낸 목록만 보고 판단하지 않는다. 목록이 다르면 화면이 본 뒤로 자료 상태가 바뀐 것이므로
     * 다시 보게 한다.
     *
     * @param acknowledgedMissing 운영자가 "이게 빠진 것을 확인했다"고 적어 낸 출처 목록.
     *                            빠진 것이 없으면 비워 둔다.
     */
    public void resolve(long reconResultId, String actor,
                        ResolveCause cause, String note, java.util.List<String> acknowledgedMissing) {
        // 주문번호는 <호출자가 준 것을 안 쓴다>. 대사 결과에서 직접 받는다. 화면이 다른 값을
        // 실어 보내면 엉뚱한 주문의 자료를 보고 확정하게 된다.
        String orderNo = reconciliation.orderNoOf(reconResultId);
        OrderTimeline timeline = timelineService.assemble(orderNo);
        java.util.List<String> missing = timeline.unavailable();

        if (!missing.isEmpty()) {
            java.util.Set<String> ack = acknowledgedMissing == null
                    ? java.util.Set.of() : java.util.Set.copyOf(acknowledgedMissing);
            if (!ack.equals(java.util.Set.copyOf(missing))) {
                // 목록이 <같아야> 통과다. "빈 목록으로 아무거나 넘기면 통과"면 게이트가 아니고,
                // 화면이 보여준 것과 다른 목록이면 그 사이에 자료가 바뀐 것이라 다시 봐야 한다.
                throw IncompleteEvidenceException.of(orderNo, missing);
            }
            log.warn("자료가 빠진 채로 확정 orderNo={} actor={} 빠진출처={}", orderNo, actor, missing);
        }

        String withEvidence = missing.isEmpty()
                ? note
                : (note == null || note.isBlank() ? "" : note + " · ")
                  + "[자료 없이 확정] 빠진 출처=" + String.join(",", missing);

        reconciliation.resolve(reconResultId, actor, cause, withEvidence);
    }
}
