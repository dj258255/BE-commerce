package com.beomsu.becommerce.live.web;

import com.beomsu.becommerce.live.LiveBroadcastService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * MediaMTX가 부르는 라이브 송출 훅(R2·R3) — 로그인 없이 누구나(실제로는 MediaMTX만) 부른다
 * (SecurityConfig가 이 컨트롤러의 경로만 permitAll). 스트림 키 자체가 자격증명이다 — 이
 * 엔드포인트들은 그 키를 검증할 뿐, 별도 인증 토큰을 요구하지 않는다.
 *
 * <p><b>운영에서는 네트워크로도 막아야 한다.</b> 지금은 MediaMTX가 commerce와 같은 네트워크
 * 안에서만 이 경로를 부른다고 전제한다(샌드박스·로컬 compose 네트워크) — 인터넷에 그대로
 * 노출하면 누구나 {@code /hooks/publish}를 직접 쳐서 가짜 송출 시작을 알릴 수 있다. 운영
 * 전환 시 리버스 프록시·방화벽으로 이 경로를 MediaMTX 호스트에서만 오게 좁히거나, 공유
 * 비밀 헤더를 추가해야 한다 — ADR-082 "다시 볼 조건".
 *
 * <p>세 엔드포인트 다 MediaMTX가 "경로(path)"로 부르는 값을 받는다 — RTMP 송출 주소
 * {@code rtmp://<host>/live/{broadcastId}}의 {@code live/{broadcastId}} 그 문자열이다
 * ({@code LiveStreamPaths}). <b>보안 수정(ADR-084)</b>: 경로에는 더 이상 스트림 키가 없다 —
 * 공개 id라 시청 화면에 노출돼도 안전하다. 실제 자격증명(스트림 키)은 {@code /auth}만
 * 따로 받는다({@code ?pass=} 쿼리 → {@code password} 필드).
 */
@RestController
@RequestMapping("/api/v1/live/hooks")
public class LiveMediaHooksController {

    private final LiveBroadcastService liveBroadcastService;

    public LiveMediaHooksController(LiveBroadcastService liveBroadcastService) {
        this.liveBroadcastService = liveBroadcastService;
    }

    /**
     * MediaMTX {@code authHTTPAddress}(R2) — 2xx면 허용, 그 외는 거절. {@code action}이
     * {@code "publish"}가 아니면(시청 등) 무조건 허용한다 — 시청 인가(R5)는 다음 단계다.
     *
     * <p>비밀은 {@code password} 필드로 온다(RTMP {@code ?pass=} 쿼리). 일부 설정에서는
     * MediaMTX가 그 필드를 안 채우고 원문 {@code query} 문자열에만 실을 수 있어, 비어 있으면
     * 거기서 직접 {@code pass=}를 뽑는다(방어적 — 실제 동작은 샌드박스 실 송출로 확인했다,
     * ADR-084).
     */
    @PostMapping("/auth")
    public ResponseEntity<Void> auth(@RequestBody MediaMtxAuthRequest request) {
        if (!"publish".equals(request.action())) {
            return ResponseEntity.ok().build();
        }
        boolean allowed = liveBroadcastService.authenticatePublish(request.path(), resolveSecret(request));
        return allowed ? ResponseEntity.ok().build() : ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    private static String resolveSecret(MediaMtxAuthRequest request) {
        if (request.password() != null && !request.password().isBlank()) {
            return request.password();
        }
        return queryParam(request.query(), "pass");
    }

    /** {@code key=value&key2=value2} 꼴의 원문 쿼리 문자열에서 하나만 뽑는다(의존 없이, 최소한만). */
    private static String queryParam(String rawQuery, String key) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return null;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                continue;
            }
            if (pair.substring(0, eq).equals(key)) {
                return pair.substring(eq + 1);
            }
        }
        return null;
    }

    /**
     * MediaMTX {@code runOnReady}(R3) — 경로가 "준비됨"(퍼블리셔가 붙어 스트림이 흐르기
     * 시작함)으로 바뀌었을 때, 즉 송출이 실제로 시작됐을 때. (옛 이름 {@code runOnPublish}는
     * MediaMTX에 없는 훅이다 — mediamtx.yml 상단 주석 참고.)
     */
    @PostMapping("/publish")
    public ResponseEntity<Void> publish(@RequestParam String path) {
        liveBroadcastService.handlePublish(path);
        return ResponseEntity.ok().build();
    }

    /**
     * MediaMTX {@code runOnNotReady}(R3) — 경로가 "준비 안 됨"으로 바뀌었을 때, 즉 송출이
     * 끊겼을 때(재접속 유예 시작, 아직 종료 아님).
     */
    @PostMapping("/unpublish")
    public ResponseEntity<Void> unpublish(@RequestParam String path) {
        liveBroadcastService.handleUnpublish(path);
        return ResponseEntity.ok().build();
    }
}
