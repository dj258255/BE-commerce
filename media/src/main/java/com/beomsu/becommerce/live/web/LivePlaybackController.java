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
 * <p><b>정직하게 남기는 한계</b>: {@code hlsUrl}은 스트림 키 원문을 포함한다(MediaMTX가 송출
 * 경로와 재생 경로를 구분하지 않는 지금 구성의 한계 — ADR-084 "다시 볼 조건"). 운영 전환
 * 전에 재생 전용 토큰으로 바꿔야 한다.
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
                ? hlsBaseUrl + "/" + LiveStreamPaths.PREFIX + broadcast.getStreamKey() + "/index.m3u8"
                : null;
        return new LivePlaybackView(broadcast.getId(), broadcast.getStatus(), hlsUrl);
    }
}
