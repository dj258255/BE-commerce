package com.beomsu.becommerce.auth;

import com.beomsu.becommerce.MetricsTestConfig;
import com.beomsu.becommerce.SecurityConfig;
import com.beomsu.becommerce.ratelimit.RateLimiter;
import com.beomsu.becommerce.auth.internal.AuthTokenService;
import com.beomsu.becommerce.auth.internal.AuthException;
import com.beomsu.becommerce.auth.AuthController;
import com.beomsu.becommerce.member.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 로그인/갱신 엔드포인트 슬라이스 테스트 — 자격증명 검증 결과에 따른 200/401, 응답 필드를 확인한다.
 * AuthenticationManager/AuthTokenService는 목으로 대체(BCrypt·Redis 실제 동작은 슬라이스 밖).
 */
@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, MetricsTestConfig.class})
class AuthControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    AuthenticationManager authenticationManager;

    @MockitoBean
    AuthTokenService authTokenService;

    @MockitoBean
    JwtDecoder jwtDecoder;

    // SecurityConfig가 시큐리티 체인에 RateLimitFilter를 끼우며 요구하는 협력자 — 슬라이스에선 목.
    // login은 이제 IP 기준 rate limit 대상이라, 한도 통과(true)를 스텁하지 않으면 필터가 429로 막는다.
    @MockitoBean
    RateLimiter rateLimiter;

    // SecurityConfig의 복합 UserDetailsService가 회원 조회에 쓰는 협력자 — JPA를 안 띄우는 웹 슬라이스라 목.
    @MockitoBean
    MemberRepository memberRepository;

    @BeforeEach
    void allowRateLimit() {
        when(rateLimiter.tryAcquire(any(), anyInt(), any())).thenReturn(true); // 한도 통과 — 필터가 막지 않게
    }

    @Test
    @DisplayName("올바른 자격증명 → 200 + token(access)/refreshToken 둘 다")
    void loginSucceeds() throws Exception {
        var authenticated = new UsernamePasswordAuthenticationToken(
                "1", null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        when(authenticationManager.authenticate(any())).thenReturn(authenticated);
        when(authTokenService.login(eq("1"), any()))
                .thenReturn(new AuthTokenService.TokenPair("access-token", "refresh-token", 1800L));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content("{\"username\":\"1\",\"password\":\"user-local-only\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("access-token"))          // 하위호환: token = access
                .andExpect(jsonPath("$.refreshToken").value("refresh-token"))  // 추가 필드
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresInSeconds").value(1800));
    }

    @Test
    @DisplayName("잘못된 자격증명 → 401 Unauthorized")
    void loginRejectsBadCredentials() throws Exception {
        when(authenticationManager.authenticate(any()))
                .thenThrow(new BadCredentialsException("bad credentials"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content("{\"username\":\"1\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("유효한 refresh → 200 + 회전된 새 access/refresh")
    void refreshSucceeds() throws Exception {
        when(authTokenService.refresh(eq("refresh-token")))
                .thenReturn(new AuthTokenService.TokenPair("new-access", "new-refresh", 1800L));

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(APPLICATION_JSON)
                        .content("{\"refreshToken\":\"refresh-token\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("new-access"))
                .andExpect(jsonPath("$.refreshToken").value("new-refresh"));
    }

    @Test
    @DisplayName("무효/폐기된 refresh → 401 Unauthorized")
    void refreshRejectsInvalidToken() throws Exception {
        when(authTokenService.refresh(any()))
                .thenThrow(new AuthException("유효하지 않은 refresh 토큰입니다."));

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(APPLICATION_JSON)
                        .content("{\"refreshToken\":\"gone\"}"))
                .andExpect(status().isUnauthorized());
    }
}
