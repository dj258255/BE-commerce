package com.beomsu.becommerce.live.web;

import com.beomsu.becommerce.MetricsTestConfig;
import com.beomsu.becommerce.SecurityConfig;
import com.beomsu.becommerce.live.LiveBroadcastService;
import com.beomsu.becommerce.member.MemberRepository;
import com.beomsu.becommerce.ratelimit.RateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * R2·R3: MediaMTX 훅({@code /api/v1/live/hooks/**})이 로그인 없이도 열려 있는지(permitAll),
 * 인증 훅의 2xx/비2xx 분기가 맞는지 웹 슬라이스로 확인한다.
 *
 * <p><b>보안 수정(ADR-084)</b>: {@code path}는 더 이상 스트림 키가 아니라 방송 공개
 * id({@code "live/5"})이고, 비밀은 {@code password} 필드(또는 {@code query}의
 * {@code pass=} — MediaMTX 설정에 따라 둘 중 하나로 온다, 컨트롤러가 둘 다 본다)로 따로 온다.
 */
@WebMvcTest({LiveMediaHooksController.class})
@Import({SecurityConfig.class, MetricsTestConfig.class})
class LiveMediaHooksControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    LiveBroadcastService liveBroadcastService;

    @MockitoBean
    RateLimiter rateLimiter;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @MockitoBean
    MemberRepository memberRepository;

    @BeforeEach
    void allowRateLimit() {
        when(rateLimiter.tryAcquire(any(), anyInt(), any())).thenReturn(true);
    }

    @Test
    @DisplayName("R2: 인증 훅은 로그인(Bearer) 없이도 불릴 수 있고, 경로(id)·비밀(password)이 맞으면 200이다")
    void authAllowsWithoutLoginWhenPublishSecretValid() throws Exception {
        when(liveBroadcastService.authenticatePublish("live/5", "KEY1")).thenReturn(true);

        mockMvc.perform(post("/api/v1/live/hooks/auth").contentType(APPLICATION_JSON)
                        .content("{\"path\":\"live/5\",\"action\":\"publish\",\"password\":\"KEY1\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("R2.1: 인증 훅은 비밀(password)이 틀리면(송출 action) 비2xx(401)로 거절한다")
    void authRejectsWhenPublishSecretInvalid() throws Exception {
        when(liveBroadcastService.authenticatePublish("live/5", "WRONG")).thenReturn(false);

        mockMvc.perform(post("/api/v1/live/hooks/auth").contentType(APPLICATION_JSON)
                        .content("{\"path\":\"live/5\",\"action\":\"publish\",\"password\":\"WRONG\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("R2.1: password 필드가 비어 있어도 query 문자열의 pass=에서 비밀을 뽑아 쓴다"
            + "(일부 MediaMTX 설정은 password를 안 채우고 query에만 싣는다)")
    void authFallsBackToQueryStringWhenPasswordFieldBlank() throws Exception {
        when(liveBroadcastService.authenticatePublish("live/5", "KEY1")).thenReturn(true);

        mockMvc.perform(post("/api/v1/live/hooks/auth").contentType(APPLICATION_JSON)
                        .content("{\"path\":\"live/5\",\"action\":\"publish\",\"password\":\"\","
                                + "\"query\":\"pass=KEY1\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("R2.1 경계: password·query 둘 다 없으면(시청 경로만 아는 사람이 비밀 없이 송출 시도) "
            + "서비스에 빈 비밀로 넘어가 거절된다")
    void authForwardsNullSecretWhenNeitherFieldPresent() throws Exception {
        when(liveBroadcastService.authenticatePublish("live/5", null)).thenReturn(false);

        mockMvc.perform(post("/api/v1/live/hooks/auth").contentType(APPLICATION_JSON)
                        .content("{\"path\":\"live/5\",\"action\":\"publish\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("R2: action이 publish가 아니면(시청 등) 비밀 검증 없이 허용한다 — 시청 인가는 다음 단계")
    void authAllowsNonPublishActionsUnconditionally() throws Exception {
        mockMvc.perform(post("/api/v1/live/hooks/auth").contentType(APPLICATION_JSON)
                        .content("{\"path\":\"live/5\",\"action\":\"read\"}"))
                .andExpect(status().isOk());

        verify(liveBroadcastService, never()).authenticatePublish(any(), any());
    }

    @Test
    @DisplayName("R3: 송출 시작 훅은 로그인 없이도 불릴 수 있고 서비스로 그대로 넘어간다")
    void publishHookForwardsPathWithoutLogin() throws Exception {
        mockMvc.perform(post("/api/v1/live/hooks/publish").param("path", "live/5"))
                .andExpect(status().isOk());

        verify(liveBroadcastService).handlePublish("live/5");
    }

    @Test
    @DisplayName("R3: 송출 종료 훅은 로그인 없이도 불릴 수 있고 서비스로 그대로 넘어간다")
    void unpublishHookForwardsPathWithoutLogin() throws Exception {
        mockMvc.perform(post("/api/v1/live/hooks/unpublish").param("path", "live/5"))
                .andExpect(status().isOk());

        verify(liveBroadcastService).handleUnpublish("live/5");
    }
}
