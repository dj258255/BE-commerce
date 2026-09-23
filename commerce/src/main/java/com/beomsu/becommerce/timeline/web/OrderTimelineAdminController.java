package com.beomsu.becommerce.timeline.web;

import com.beomsu.becommerce.timeline.OrderTimeline;
import com.beomsu.becommerce.timeline.OrderTimelineService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 주문 한 건의 전 과정 조회 (ADR-011).
 *
 * <p><b>어드민 전용</b>이다. 한 주문의 원장·정산·분쟁·대사까지 전부 드러내므로
 * 일반 사용자에게 열면 과다 노출이다. {@code /api/v1/admin/**}는 시큐리티에서
 * {@code ROLE_ADMIN}으로 잠겨 있다.
 *
 * <p>읽기 전용이다. 이 컨트롤러에 쓰기를 추가하면 조회 모듈이 도메인 규칙을 우회하는
 * 뒷문이 된다.
 */
@RestController
@RequestMapping("/api/v1/admin/orders")
class OrderTimelineAdminController {

    private final OrderTimelineService service;

    OrderTimelineAdminController(OrderTimelineService service) {
        this.service = service;
    }

    /**
     * 주문번호 하나로 전 도메인의 사실을 시간순으로 받는다.
     *
     * <p>존재하지 않는 주문이어도 <b>404가 아니라 빈 타임라인</b>을 준다.
     * 대사에서 "외부에만 있는 건"(EXTERNAL_ONLY)을 조사할 때 내부에 주문이 없는 것이
     * 정상적인 조사 대상이기 때문이다 — 404를 주면 그 조사를 못 한다.
     */
    @GetMapping("/{orderNo}/timeline")
    OrderTimeline timeline(@PathVariable String orderNo) {
        return service.assemble(orderNo);
    }
}
