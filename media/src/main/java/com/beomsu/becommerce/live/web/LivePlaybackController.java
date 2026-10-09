package com.beomsu.becommerce.live.web;

import com.beomsu.becommerce.live.LiveBroadcastException;
import com.beomsu.becommerce.live.LiveBroadcastRepository;
import com.beomsu.becommerce.live.LiveBroadcastStatus;
import com.beomsu.becommerce.live.LiveStreamPaths;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 비로그인 시청자용 재생 정보(R5 원칙 — 숏폼 피드·HLS 재생과 같은 공개 수준, SecurityConfig가
 * 이 경로만 permitAll). {@code LiveBroadcastController}(판매자 전용, 스트림 키 포함)와
 * 분리해 둔 이유도 같다 — "이 엔드포인트만 공개"라는 사실을 코드로 드러낸다.
 *
 * <p><b>보안 수정(ADR-084)</b>: {@code hlsUrl}은 방송 공개 id만 담는다 — 예전에는 스트림 키
 * 원문이 그대로 들어가 있었다(송출 경로와 재생 경로가 같았던 시절의 구성 — 시청자 누구나
 * 그 키로 대신 송출할 수 있는 위험이 있었다, R1.2가 막으려는 바로 그 위험). 지금은 MediaMTX
 * 경로 자체가 {@code live/{broadcastId}}라 안전하게 공개할 수 있다 — 송출 인증은
 * {@code LiveMediaHooksController}가 별도 비밀로만 받는다.
 */
@RestController
@RequestMapping("/api/v1/live/broadcasts")
public class LivePlaybackController {

    private final LiveBroadcastRepository broadcastRepository;
    private final String hlsBaseUrl;

    public LivePlaybackController(LiveBroadcastRepository broadcastRepository,
            @Value("${app.live.mediamtx.hls-base-url:http://localhost:8888}") String hlsBaseUrl) {
        this.broadcastRepository = broadcastRepository;
        this.hlsBaseUrl = hlsBaseUrl;
    }

    @GetMapping("/{id}/playback")
    public LivePlaybackView playback(@PathVariable long id) {
        var broadcast = broadcastRepository.findById(id)
                .orElseThrow(() -> LiveBroadcastException.notFound(id));
        String hlsUrl = broadcast.getStatus() == LiveBroadcastStatus.LIVE
                ? hlsBaseUrl + "/" + LiveStreamPaths.pathFor(broadcast.getId()) + "/index.m3u8"
                : null;
        return new LivePlaybackView(broadcast.getId(), broadcast.getStatus(), hlsUrl, broadcast.getTitle());
    }
}
