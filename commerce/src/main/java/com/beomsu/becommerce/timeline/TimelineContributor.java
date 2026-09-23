package com.beomsu.becommerce.timeline;

import java.util.List;

/**
 * 한 도메인이 주문 하나에 대해 내놓는 사실들 (ADR-011).
 *
 * <p><b>왜 인터페이스인가</b>: 조립기가 각 모듈의 조회 코드를 직접 알면, 모듈이 늘 때마다
 * 조립기를 고쳐야 하고 조립기가 모든 도메인 지식을 떠안는다. 대신 <b>각 도메인이 자기 조각을
 * 어떻게 요약할지 스스로 정하고</b>, 조립기는 모아서 정렬만 한다.
 *
 * <p>이건 대규모 인시던트 대응에서 확립된 형태와 같다 — 넓은 컨텍스트를 한 곳에서 다 처리하려
 * 하면 복잡도와 오류가 커지므로, <b>작은 도구들이 각자 좁은 범위를 담당</b>하고 위에서 조립한다
 * (12 문서 2-1의 eBay Explainers).
 *
 * <p><b>구현 규칙</b>
 * <ul>
 *   <li>읽기만 한다. 상태를 바꾸지 않는다</li>
 *   <li>주문이 그 도메인과 무관하면 <b>빈 목록</b>을 준다. 예외를 던지지 않는다 —
 *       포인트를 안 쓴 주문은 정상이지 오류가 아니다</li>
 *   <li>요약 문장은 코드가 만든다. 금액 계산을 문자열 조립 과정에서 하지 않는다</li>
 * </ul>
 */
public interface TimelineContributor {

    /**
     * 이 도메인이 아는 사실을 정규화해 돌려준다. 정렬은 조립기가 하므로 순서는 상관없다.
     *
     * @param orderNo 주문 번호. <b>모든 도메인이 이 키를 쓰지는 않는다</b> — 결제 이력은
     *                payment_id로, 원장은 (txType, sourceType, sourceId)로 묶인다.
     *                그 둘은 orderNo에서 paymentId를 해석한 뒤 조회한다(ADR-011 트레이드오프 5)
     */
    List<TimelineEntry> contribute(String orderNo);

    /** 어느 도메인인지. 하나가 실패해도 나머지를 살리려면 누가 실패했는지 알아야 한다. */
    TimelineEntry.Source source();
}
