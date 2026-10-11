package com.beomsu.becommerce.live.web;

import com.beomsu.becommerce.live.LivePinEventView;
import com.beomsu.becommerce.live.LivePinService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

/**
 * 상품 고정·해제·가격 변경 REST 컨트롤러(R8·R9) — 판매자 전용(SecurityConfig가 ROLE_SELLER로
 * 잠근다, 숏폼 업로드·라이브 방송 생성과 같은 패턴). 응답({@link LivePinEventView})이 바로
 * 시청자에게 보낸 WebSocket 메시지와 같은 모양이다 — 판매자 화면도 그 응답으로 "지금 상태"를
 * 알 수 있다.
 *
 * <p>sellerId는 인증 principal에서 얻는다(IDOR 방지) — 남의 방송에 상품을 고정할 방법이 없다.
 */
@RestController
@RequestMapping("/api/v1/live/broadcasts/{id}/pin")
public class LivePinController {

    private final LivePinService livePinService;

    public LivePinController(LivePinService livePinService) {
        this.livePinService = livePinService;
    }

    /** 고정(R8.1·R8.2) — 이미 고정된 상품이 있어도 그냥 덮어쓴다(이전 고정 자동 해제). */
    @PostMapping
    public LivePinEventView pin(@PathVariable long id, @RequestBody PinProductRequest request,
            Principal principal) {
        return livePinService.pin(sellerId(principal), id, request.productId(), request.price(),
                request.limitedQuantity());
    }

    /** 해제 — 고정된 상품이 없어도 성공(멱등)한다. */
    @DeleteMapping
    public LivePinEventView unpin(@PathVariable long id, Principal principal) {
        return livePinService.unpin(sellerId(principal), id);
    }

    /** 가격 변경(R9.1) — 지금 고정된 상품이 없으면 409(NOTHING_PINNED)로 거절된다. */
    @PatchMapping("/price")
    public LivePinEventView changePrice(@PathVariable long id, @RequestBody ChangePinPriceRequest request,
            Principal principal) {
        return livePinService.changePrice(sellerId(principal), id, request.price());
    }

    private static long sellerId(Principal principal) {
        return Long.parseLong(principal.getName());
    }
}
