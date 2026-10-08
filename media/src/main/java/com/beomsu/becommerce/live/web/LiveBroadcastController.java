package com.beomsu.becommerce.live.web;

import com.beomsu.becommerce.live.LiveBroadcastService;
import com.beomsu.becommerce.live.LiveBroadcastView;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

/**
 * 라이브 방송 판매자 API(R1) — 판매자 전용(SecurityConfig가 ROLE_SELLER로 잠근다, 숏폼
 * 업로드와 같은 패턴).
 *
 * <p>sellerId는 전부 인증 principal에서 얻는다(IDOR 방지) — 요청 본문·경로에 sellerId가 없다.
 */
@RestController
@RequestMapping("/api/v1/live/broadcasts")
public class LiveBroadcastController {

    private final LiveBroadcastService liveBroadcastService;

    public LiveBroadcastController(LiveBroadcastService liveBroadcastService) {
        this.liveBroadcastService = liveBroadcastService;
    }

    /** 방송 생성(R1.1) — 201 + 방송 id·제목·SCHEDULED·방송 전용 스트림 키. */
    @PostMapping
    public ResponseEntity<LiveBroadcastView> create(@RequestBody CreateLiveBroadcastRequest request,
            Principal principal) {
        LiveBroadcastView view = liveBroadcastService.create(sellerId(principal), request.title());
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    /** 방송주 본인 조회(R1) — 다른 판매자는 403(키가 응답에 없다, {@code LiveBroadcastException#forbidden}). */
    @GetMapping("/{id}")
    public LiveBroadcastView get(@PathVariable long id, Principal principal) {
        return liveBroadcastService.get(sellerId(principal), id);
    }

    private long sellerId(Principal principal) {
        return Long.parseLong(principal.getName());
    }
}
