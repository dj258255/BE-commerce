package com.beomsu.becommerce.shorts.web;

import com.beomsu.becommerce.shorts.ShortsMediaService;
import org.springframework.core.io.UrlResource;
import org.springframework.core.io.support.ResourceRegion;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * 숏폼 변환 산출물(HLS 재생목록·세그먼트·썸네일) 서빙 컨트롤러(R26 재생) — 로그인 없이 누구나
 * 부른다(SecurityConfig가 이 컨트롤러의 경로(GET, {@code /{id}/media} 아래)만 permitAll —
 * 피드와 같은 공개 수준, R26 비로그인 시청).
 *
 * <p><b>운영에서는 이 컨트롤러가 없어진다.</b> 로컬 파일 저장소를 전제한 개발용 경계다 — 운영은
 * 오브젝트 스토리지(MinIO/S3)·CDN이 이 역할을 한다(presigned GET 또는 CDN 오리진 연결로,
 * Spring 프로세스가 비디오 바이트를 중계하지 않는다). 자세한 근거와 다시 볼 조건은 ADR-081.
 *
 * <p>{@code Range} 요청을 지원한다({@code ResourceRegion} 반환 — Spring이
 * {@code ResourceRegionHttpMessageConverter}로 206/부분 바이트·{@code Content-Range}를 알아서
 * 쓴다) — 브라우저·hls.js가 세그먼트를 부분적으로 받거나 다시 받을 때 필요하다.
 */
@RestController
@RequestMapping("/api/v1/shorts")
public class ShortsMediaController {

    private final ShortsMediaService mediaService;

    public ShortsMediaController(ShortsMediaService mediaService) {
        this.mediaService = mediaService;
    }

    /**
     * {@code path}는 마스터 재생목록이 있는 디렉터리 기준 상대 경로다(예: {@code master.m3u8},
     * {@code 1080/out.m3u8}, {@code thumb.jpg}) — {@code ShortsMediaService#resolve}가
     * {@code ../} 같은 경로 조작을 막는다(R26).
     */
    @GetMapping("/{id}/media/{*path}")
    public ResponseEntity<ResourceRegion> media(@PathVariable long id, @PathVariable String path,
            @RequestHeader HttpHeaders headers) throws IOException {
        String relativePath = path.startsWith("/") ? path.substring(1) : path;
        Path file = mediaService.resolve(id, relativePath);
        UrlResource resource = new UrlResource(file.toUri());
        long contentLength = resource.contentLength();
        MediaType mediaType = mediaTypeFor(file);

        List<HttpRange> ranges = headers.getRange();
        if (ranges.isEmpty()) {
            ResourceRegion region = new ResourceRegion(resource, 0, contentLength);
            return ResponseEntity.ok()
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .contentType(mediaType)
                    .body(region);
        }
        ResourceRegion region = ranges.get(0).toResourceRegion(resource);
        return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .contentType(mediaType)
                .body(region);
    }

    /** m3u8·m4s·mp4·jpg만 안다 — 이 모듈이 실제로 만드는 파일 형식(R23 FfmpegTranscodeRunner)뿐이다. */
    private static MediaType mediaTypeFor(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".m3u8")) {
            return MediaType.valueOf("application/vnd.apple.mpegurl");
        }
        if (name.endsWith(".m4s")) {
            return MediaType.valueOf("video/iso.segment");
        }
        if (name.endsWith(".mp4")) {
            return MediaType.valueOf("video/mp4");
        }
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return MediaType.IMAGE_JPEG;
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
