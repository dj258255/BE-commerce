package com.beomsu.becommerce.live.web;

import com.beomsu.becommerce.MetricsTestConfig;
import com.beomsu.becommerce.SecurityConfig;
import com.beomsu.becommerce.live.LiveBroadcastException;
import com.beomsu.becommerce.live.LiveBroadcastService;
import com.beomsu.becommerce.live.LiveBroadcastStatus;
import com.beomsu.becommerce.live.LiveBroadcastView;
import com.beomsu.becommerce.member.MemberRepository;
import com.beomsu.becommerce.ratelimit.RateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * R1: 방송 생성·조회({@code /api/v1/live/broadcasts}) 판매자 전용 인가와 응답 모양을 웹
 * 슬라이스로 확인한다.
 */
@WebMvcTest(LiveBroadcastController.class)
@Import({SecurityConfig.class, MetricsTestConfig.class})
class LiveBroadcastControllerTest {

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

    private static RequestPostProcessor seller(String sellerId) {
        return jwt().jwt(builder -> builder.subject(sellerId))
                .authorities(new SimpleGrantedAuthority("ROLE_SELLER"));
    }

    @Test
    @DisplayName("R1.1: 판매자가 제목을 보내 방송을 만들면 201 + 제목 + SCHEDULED + 스트림 키를 받는다")
    void createReturns201WithScheduledStatusAndStreamKey() throws Exception {
        when(liveBroadcastService.create(3L, "오늘의 방송")).thenReturn(
                new LiveBroadcastView(1L, LiveBroadcastStatus.SCHEDULED, "STREAMKEY123", "오늘의 방송",
                        Instant.now(), null, null));

        mockMvc.perform(post("/api/v1/live/broadcasts").with(seller("3")).with(csrf())
                        .contentType(APPLICATION_JSON).content("{\"title\":\"오늘의 방송\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SCHEDULED"))
                .andExpect(jsonPath("$.title").value("오늘의 방송"))
                .andExpect(jsonPath("$.streamKey").value("STREAMKEY123"));
    }

    @Test
    @DisplayName("R1: 인증 없이 방송을 만들려 하면 401")
    void createWithoutLoginIsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/v1/live/broadcasts").with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("R1: 일반 사용자(ROLE_USER)는 방송을 만들 수 없다 — 403")
    void createByRegularUserIsForbidden() throws Exception {
        mockMvc.perform(post("/api/v1/live/broadcasts")
                        .with(jwt().jwt(b -> b.subject("1")).authorities(new SimpleGrantedAuthority("ROLE_USER")))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("R1.2: 다른 판매자(판매자 A)의 방송을 조회하면 403이고 스트림 키가 응답에 없다")
    void getOthersBroadcastIsForbiddenWithoutLeakingKey() throws Exception {
        when(liveBroadcastService.get(eq(9L), eq(5L))).thenThrow(LiveBroadcastException.forbidden(5L));

        mockMvc.perform(get("/api/v1/live/broadcasts/5").with(seller("9")))
                .andExpect(status().isForbidden())
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString();
                    org.assertj.core.api.Assertions.assertThat(body).doesNotContain("STREAMKEY");
                });
    }

    @Test
    @DisplayName("R1: 방송주 본인 조회는 200이고 스트림 키를 포함한다")
    void getOwnBroadcastReturnsStreamKey() throws Exception {
        when(liveBroadcastService.get(3L, 1L)).thenReturn(
                new LiveBroadcastView(1L, LiveBroadcastStatus.LIVE, "STREAMKEY123", "오늘의 방송",
                        Instant.now(), Instant.now(), null));

        mockMvc.perform(get("/api/v1/live/broadcasts/1").with(seller("3")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.streamKey").value("STREAMKEY123"))
                .andExpect(jsonPath("$.status").value("LIVE"));
    }
}
