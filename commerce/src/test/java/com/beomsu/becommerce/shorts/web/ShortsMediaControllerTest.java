package com.beomsu.becommerce.shorts.web;

import com.beomsu.becommerce.MetricsTestConfig;
import com.beomsu.becommerce.SecurityConfig;
import com.beomsu.becommerce.member.MemberRepository;
import com.beomsu.becommerce.ratelimit.RateLimiter;
import com.beomsu.becommerce.shorts.ShortsException;
import com.beomsu.becommerce.shorts.ShortsMediaService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * R26: 숏폼 변환 산출물 서빙({@code GET /api/v1/shorts/{id}/media/**})이 비로그인에서도 열려
 * 있는지, Content-Type·Range·경로 조작 방어가 맞는지 웹 슬라이스로 확인한다.
 * {@link ShortsMediaService}는 목으로 대체하고(실제 파일은 {@code @TempDir}에 둔다), 그 위의
 * HTTP 계층(컨트롤러의 Range 처리·Content-Type 판정, SecurityConfig의 permitAll)만 본다.
 */
@WebMvcTest(ShortsMediaController.class)
@Import({SecurityConfig.class, MetricsTestConfig.class})
class ShortsMediaControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    ShortsMediaService mediaService;

    @MockitoBean
    RateLimiter rateLimiter;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @MockitoBean
    MemberRepository memberRepository;

    @TempDir
    Path tempDir;

    private Path writeFile(String name, byte[] content) throws IOException {
        Path file = tempDir.resolve(name);
        Files.write(file, content);
        return file;
    }

    @Test
    @DisplayName("R26: 비로그인(토큰 없음)으로 마스터 재생목록을 받으면 200 + application/vnd.apple.mpegurl")
    void masterPlaylistIsPubliclyServedWithCorrectContentType() throws Exception {
        Path playlist = writeFile("master.m3u8", "#EXTM3U\n".getBytes());
        when(mediaService.resolve(5L, "master.m3u8")).thenReturn(playlist);

        mockMvc.perform(get("/api/v1/shorts/5/media/master.m3u8"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/vnd.apple.mpegurl"))
                .andExpect(header().string("Accept-Ranges", "bytes"))
                .andExpect(content().bytes("#EXTM3U\n".getBytes()));
    }

    @Test
    @DisplayName("R26: 썸네일(.jpg)은 image/jpeg로, 세그먼트(.m4s)는 video/iso.segment로 내려간다")
    void contentTypeIsChosenByExtension() throws Exception {
        Path thumb = writeFile("thumb.jpg", new byte[] {1, 2, 3});
        when(mediaService.resolve(5L, "thumb.jpg")).thenReturn(thumb);
        Path segment = writeFile("out0.m4s", new byte[] {4, 5, 6, 7});
        when(mediaService.resolve(5L, "1080/out0.m4s")).thenReturn(segment);

        mockMvc.perform(get("/api/v1/shorts/5/media/thumb.jpg"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"));

        mockMvc.perform(get("/api/v1/shorts/5/media/1080/out0.m4s"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "video/iso.segment"));
    }

    @Test
    @DisplayName("R26: Range 헤더가 있으면 206 Partial Content로 요청한 바이트 구간만 돌려준다")
    void rangeRequestReturnsPartialContent() throws Exception {
        byte[] bytes = "0123456789".getBytes();
        Path segment = writeFile("out0.m4s", bytes);
        when(mediaService.resolve(5L, "1080/out0.m4s")).thenReturn(segment);

        mockMvc.perform(get("/api/v1/shorts/5/media/1080/out0.m4s").header("Range", "bytes=2-5"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Range", "bytes 2-5/10"))
                .andExpect(content().bytes("2345".getBytes()));
    }

    @Test
    @DisplayName("R26 경계: 서비스가 거절하면(경로 조작·READY 아님·존재하지 않는 id 등) 404로 응답한다 — "
            + "실제 경로 조작 방어 자체는 ShortsMediaServiceTest가 본다")
    void rejectionFromServiceBecomesNotFound() throws Exception {
        when(mediaService.resolve(anyLong(), eq("master.m3u8"))).thenThrow(ShortsException.notFound(999L));

        mockMvc.perform(get("/api/v1/shorts/999/media/master.m3u8"))
                .andExpect(status().isNotFound());
    }
}
