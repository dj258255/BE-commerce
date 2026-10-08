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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * R2·R3: MediaMTX 훅({@code /api/v1/live/hooks/**})이 로그인 없이도 열려 있는지(permitAll),
 * 인증 훅의 2xx/비2xx 분기가 맞는지 웹 슬라이스로 확인한다.
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
    @DisplayName("R2: 인증 훅은 로그인(Bearer) 없이도 불릴 수 있고, 스트림 키가 맞으면 200이다")
    void authAllowsWithoutLoginWhenPublishKeyValid() throws Exception {
        when(liveBroadcastService.authenticatePublish("live/KEY1")).thenReturn(true);

        mockMvc.perform(post("/api/v1/live/hooks/auth").contentType(APPLICATION_JSON)
                        .content("{\"path\":\"live/KEY1\",\"action\":\"publish\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("R2.1: 인증 훅은 스트림 키가 틀리면(송출 action) 비2xx(401)로 거절한다")
    void authRejectsWhenPublishKeyInvalid() throws Exception {
        when(liveBroadcastService.authenticatePublish("live/WRONG")).thenReturn(false);

        mockMvc.perform(post("/api/v1/live/hooks/auth").contentType(APPLICATION_JSON)
                        .content("{\"path\":\"live/WRONG\",\"action\":\"publish\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("R2: action이 publish가 아니면(시청 등) 키 검증 없이 허용한다 — 시청 인가는 다음 단계")
    void authAllowsNonPublishActionsUnconditionally() throws Exception {
        mockMvc.perform(post("/api/v1/live/hooks/auth").contentType(APPLICATION_JSON)
                        .content("{\"path\":\"live/anything\",\"action\":\"read\"}"))
                .andExpect(status().isOk());

        verify(liveBroadcastService, org.mockito.Mockito.never()).authenticatePublish(any());
    }

    @Test
    @DisplayName("R3: 송출 시작 훅은 로그인 없이도 불릴 수 있고 서비스로 그대로 넘어간다")
    void publishHookForwardsPathWithoutLogin() throws Exception {
        mockMvc.perform(post("/api/v1/live/hooks/publish").param("path", "live/KEY1"))
                .andExpect(status().isOk());

        verify(liveBroadcastService).handlePublish("live/KEY1");
    }

    @Test
    @DisplayName("R3: 송출 종료 훅은 로그인 없이도 불릴 수 있고 서비스로 그대로 넘어간다")
    void unpublishHookForwardsPathWithoutLogin() throws Exception {
        mockMvc.perform(post("/api/v1/live/hooks/unpublish").param("path", "live/KEY1"))
                .andExpect(status().isOk());

        verify(liveBroadcastService).handleUnpublish("live/KEY1");
    }
}
