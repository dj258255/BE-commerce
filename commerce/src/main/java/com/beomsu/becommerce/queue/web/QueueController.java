package com.beomsu.becommerce.queue.web;

import com.beomsu.becommerce.queue.QueuePosition;
import com.beomsu.becommerce.queue.QueueService;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.Map;

/**
 * 선착순 대기열 REST 컨트롤러.
 *
 * <p>대기열 참가자(ROLE_USER)는 인증된 principal로 식별한다 — 멤버(userId)를 클라이언트가 보내지 않고
 * 인증 컨텍스트에서 얻어(남의 자리로 새치기 방지) ZSET 멤버로 쓴다.
 *
 * <p>클라이언트 흐름(참고): {@code POST enter} → 응답의 {@code admitted}가 false면 {@code GET status}를
 * 폴링하며 순번이 앞으로 오길 기다린다 → {@code admitted}가 true가 되면 그때 결제 API로 진행하고,
 * 결제가 끝나면 {@code POST leave}로 줄에서 빠진다. 결제 경로와는 <b>기본 독립</b>이지만, 게이트
 * 상품({@code app.queue.gate.product-ids})에 한해서는 주문 생성 시 서버가 입장권을 강제한다(옵트인) —
 * admitted 판정 순간 발급되는 입장권이 없으면 주문이 거절된다.
 */
@RestController
@RequestMapping("/api/v1/queue")
public class QueueController {

    private final QueueService queueService;

    public QueueController(QueueService queueService) {
        this.queueService = queueService;
    }

    /** 대기열 입장(줄 서기). 재호출은 멱등 — 기존 순번을 돌려준다. */
    @PostMapping("/{eventId}/enter")
    public QueuePosition enter(@PathVariable String eventId, Principal principal) {
        return queueService.enter(eventId, principal.getName());
    }

    /** 현재 순번/입장 여부 조회(폴링용). */
    @GetMapping("/{eventId}/status")
    public QueuePosition status(@PathVariable String eventId, Principal principal) {
        return queueService.status(eventId, principal.getName());
    }

    /** 대기열 이탈 — 결제 완료 또는 자발적 포기 시 줄에서 빠진다(뒷사람 순번 당김). */
    @PostMapping("/{eventId}/leave")
    public Map<String, Boolean> leave(@PathVariable String eventId, Principal principal) {
        queueService.leave(eventId, principal.getName());
        return Map.of("left", true);
    }
}
