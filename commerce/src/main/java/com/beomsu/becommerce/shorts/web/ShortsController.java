package com.beomsu.becommerce.shorts.web;

import com.beomsu.becommerce.shorts.ShortVideoView;
import com.beomsu.becommerce.shorts.ShortsService;
import com.beomsu.becommerce.shorts.UploadMeta;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

/**
 * 숏폼 업로드 REST 컨트롤러(R21) — 판매자 전용(SecurityConfig가 ROLE_SELLER로 잠근다).
 *
 * <p>sellerId는 전부 인증 principal에서 얻는다(IDOR 방지) — 요청 본문·경로에 sellerId가 없다.
 */
@RestController
@RequestMapping("/api/v1/shorts")
public class ShortsController {

    private final ShortsService shortsService;

    public ShortsController(ShortsService shortsService) {
        this.shortsService = shortsService;
    }

    /** 업로드 시작 — 메타 검증(60초·200MB·9:16) 통과 시 숏폼 id와 업로드 URL을 돌려준다. */
    @PostMapping
    public StartUploadResponse start(@RequestBody StartUploadRequest request, Principal principal) {
        UploadMeta meta = new UploadMeta(request.durationSeconds(), request.fileSizeBytes(),
                request.width(), request.height(), request.contentType());
        var result = shortsService.startUpload(sellerId(principal), meta);
        return StartUploadResponse.from(result);
    }

    /** 업로드 완료 알림 — 저장소에 파일이 실제로 있을 때만 UPLOADED로 전이한다. */
    @PostMapping("/{id}/complete")
    public ShortVideoView complete(@PathVariable long id, Principal principal) {
        return shortsService.completeUpload(sellerId(principal), id);
    }

    @GetMapping("/{id}")
    public ShortVideoView get(@PathVariable long id, Principal principal) {
        return shortsService.get(sellerId(principal), id);
    }

    private long sellerId(Principal principal) {
        return Long.parseLong(principal.getName());
    }
}
