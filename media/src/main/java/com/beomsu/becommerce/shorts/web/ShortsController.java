package com.beomsu.becommerce.shorts.web;

import com.beomsu.becommerce.shorts.ShortVideoView;
import com.beomsu.becommerce.shorts.ShortsService;
import com.beomsu.becommerce.shorts.UploadMeta;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

/**
 * 숏폼 업로드·상품 연결 REST 컨트롤러(R21·R25) — 판매자 전용(SecurityConfig가 ROLE_SELLER로 잠근다).
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

    /** 상품 연결(R25) — 없는 상품이면 404, 영상당 최대 개수를 넘으면 409로 거절된다. */
    @PostMapping("/{id}/products")
    public ShortVideoView linkProduct(@PathVariable long id, @RequestBody LinkProductRequest request,
                                      Principal principal) {
        return shortsService.linkProduct(sellerId(principal), id, request.productId());
    }

    /** 상품 연결 해제(R25) — 연결돼 있지 않은 상품이어도 성공(멱등)한다. */
    @DeleteMapping("/{id}/products/{productId}")
    public ShortVideoView unlinkProduct(@PathVariable long id, @PathVariable long productId,
                                        Principal principal) {
        return shortsService.unlinkProduct(sellerId(principal), id, productId);
    }

    private long sellerId(Principal principal) {
        return Long.parseLong(principal.getName());
    }
}
