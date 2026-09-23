package com.beomsu.becommerce.ratelimit;

import com.beomsu.becommerce.ratelimit.RateLimiter;
import com.beomsu.becommerce.ratelimit.RateLimitFilter;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RateLimitFilter 단위 테스트 — MockHttpServletRequest/Response + RateLimiter 목.
 * 대상 경로 429(직접 write + Retry-After), 비대상/미인증/disabled 통과를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class RateLimitFilterTest {

    private static final int PER_USER = 5;
    private static final int GLOBAL = 100;

    @Mock
    RateLimiter rateLimiter;

    MockHttpServletResponse response;
    MockFilterChain chain;

    @BeforeEach
    void setUp() {
        response = new MockHttpServletResponse();
        chain = new MockFilterChain();
        // BearerTokenAuthenticationFilter 뒤에 놓인 상황을 재현 — 인증이 이미 확정돼 있다.
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("1", null, "ROLE_USER"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /** 계측은 실제 레지스트리로 받는다 — 목이면 카운터 태그가 맞는지 검증할 수 없다. */
    final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private RateLimitFilter filter(boolean enabled) {
        return new RateLimitFilter(rateLimiter, enabled, PER_USER, GLOBAL, meterRegistry);
    }

    /** 특정 층·경로의 거절 카운터 값. 없으면 0. */
    private double rejectedCount(String scope, String path) {
        var counter = meterRegistry.find("ratelimit.rejected")
                .tag("scope", scope).tag("path", path).counter();
        return counter == null ? 0 : counter.count();
    }

    private static MockHttpServletRequest post(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setRequestURI(uri);
        return request;
    }

    @Test
    @DisplayName("대상 경로 + per-user 한도 초과: 429 + Retry-After:1 + RATE_LIMITED JSON, 체인 진행 안 함")
    void overLimitRejectedWith429() throws ServletException, IOException {
        when(rateLimiter.tryAcquire(eq("user:1:/api/v1/orders"), eq(PER_USER), any(Duration.class)))
                .thenReturn(false);

        filter(true).doFilter(post("/api/v1/orders"), response, chain);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("1");
        assertThat(response.getContentType()).contains("application/json");
        assertThat(response.getContentAsString()).contains("RATE_LIMITED");
        assertThat(response.getHeader("X-RateLimit-Scope")).isEqualTo("user");  // 이 사용자만 물러나면 됨
        // 지표가 없으면 운영에서 한도를 조정할 근거가 안 생긴다 — 층·경로별로 세는지 확인한다.
        assertThat(rejectedCount("user", "/api/v1/orders")).isEqualTo(1);
        assertThat(rejectedCount("global", "/api/v1/orders")).isZero();
        assertThat(chain.getRequest()).isNull();                    // 컨트롤러까지 안 감(싸게 거절)
        // per-user에서 이미 초과 → global 카운터는 소비하지 않는다.
        verify(rateLimiter, never()).tryAcquire(eq("global:/api/v1/orders"), anyInt(), any(Duration.class));
    }

    @Test
    @DisplayName("global 한도 초과(per-user는 통과): 429")
    void globalOverLimitRejected() throws ServletException, IOException {
        when(rateLimiter.tryAcquire(eq("user:1:/api/v1/payments/confirm"), eq(PER_USER), any(Duration.class)))
                .thenReturn(true);
        when(rateLimiter.tryAcquire(eq("global:/api/v1/payments/confirm"), eq(GLOBAL), any(Duration.class)))
                .thenReturn(false);

        filter(true).doFilter(post("/api/v1/payments/confirm"), response, chain);

        assertThat(response.getStatus()).isEqualTo(429);
        // 시스템 전체 포화 — 이 클라이언트가 얌전해져도 안 풀린다. user와 대응이 정반대라 구분이 필요하다.
        assertThat(response.getHeader("X-RateLimit-Scope")).isEqualTo("global");
        assertThat(rejectedCount("global", "/api/v1/payments/confirm")).isEqualTo(1);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    @DisplayName("대상 경로 + 한도 이내: per-user·global 둘 다 확인 후 통과")
    void withinLimitPasses() throws ServletException, IOException {
        when(rateLimiter.tryAcquire(anyString(), anyInt(), any(Duration.class))).thenReturn(true);

        filter(true).doFilter(post("/api/v1/orders"), response, chain);

        assertThat(chain.getRequest()).isNotNull();                 // 체인 계속
        assertThat(response.getStatus()).isEqualTo(200);
        verify(rateLimiter).tryAcquire(eq("user:1:/api/v1/orders"), eq(PER_USER), any(Duration.class));
        verify(rateLimiter).tryAcquire(eq("global:/api/v1/orders"), eq(GLOBAL), any(Duration.class));
    }

    @Test
    @DisplayName("비대상 경로(조회 등)는 rate limiter를 아예 타지 않고 통과")
    void nonTargetPathBypasses() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/orders/ord-1");
        request.setRequestURI("/api/v1/orders/ord-1");

        filter(true).doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        verify(rateLimiter, never()).tryAcquire(anyString(), anyInt(), any(Duration.class));
    }

    @Test
    @DisplayName("정확 매칭: /api/v1/orders/xxx 같은 하위 경로 POST는 대상이 아니다")
    void subPathIsNotTarget() throws ServletException, IOException {
        filter(true).doFilter(post("/api/v1/orders/ord-1/cancel"), response, chain);

        assertThat(chain.getRequest()).isNotNull();
        verify(rateLimiter, never()).tryAcquire(anyString(), anyInt(), any(Duration.class));
    }

    @Test
    @DisplayName("disabled(app.ratelimit.enabled=false)면 대상 경로도 통과 — k6 전/후 비교 스위치")
    void disabledPassesThrough() throws ServletException, IOException {
        filter(false).doFilter(post("/api/v1/orders"), response, chain);

        assertThat(chain.getRequest()).isNotNull();
        verify(rateLimiter, never()).tryAcquire(anyString(), anyInt(), any(Duration.class));
    }

    @Test
    @DisplayName("미인증 요청은 통과(뒤의 시큐리티가 401 처리) — 익명 버킷 오폭 방지")
    void unauthenticatedPassesThrough() throws ServletException, IOException {
        SecurityContextHolder.clearContext();

        filter(true).doFilter(post("/api/v1/orders"), response, chain);

        assertThat(chain.getRequest()).isNotNull();
        verify(rateLimiter, never()).tryAcquire(anyString(), anyInt(), any(Duration.class));
    }

    @Test
    @DisplayName("로그인은 미인증이라 IP로 제한한다 — 초과 시 429 + scope:ip, global 미소비")
    void loginLimitedByIpNotPrincipal() throws ServletException, IOException {
        SecurityContextHolder.clearContext();          // 로그인 시점엔 principal이 없다
        MockHttpServletRequest request = post("/api/v1/auth/login");
        request.setRemoteAddr("10.0.0.7");
        when(rateLimiter.tryAcquire(eq("ip:10.0.0.7:/api/v1/auth/login"), eq(PER_USER), any(Duration.class)))
                .thenReturn(false);

        filter(true).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("X-RateLimit-Scope")).isEqualTo("ip");
        assertThat(chain.getRequest()).isNull();       // Argon2 검증(~32ms)까지 가지 않는다 — 비대칭 DoS 차단
        verify(rateLimiter, never())
                .tryAcquire(eq("global:/api/v1/auth/login"), anyInt(), any(Duration.class));
    }

    @Test
    @DisplayName("가입도 같은 IP 제한을 탄다 — 한도 이내면 통과")
    void signupWithinIpLimitPasses() throws ServletException, IOException {
        SecurityContextHolder.clearContext();
        MockHttpServletRequest request = post("/api/v1/members/signup");
        request.setRemoteAddr("10.0.0.8");
        when(rateLimiter.tryAcquire(anyString(), anyInt(), any(Duration.class))).thenReturn(true);

        filter(true).doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        verify(rateLimiter).tryAcquire(eq("ip:10.0.0.8:/api/v1/members/signup"), eq(PER_USER), any(Duration.class));
        verify(rateLimiter).tryAcquire(eq("global:/api/v1/members/signup"), eq(GLOBAL), any(Duration.class));
    }

    @Test
    @DisplayName("로그인 전체 한도 초과: scope:global — 한 IP가 얌전해도 안 풀린다")
    void loginGlobalOverLimitRejected() throws ServletException, IOException {
        SecurityContextHolder.clearContext();
        MockHttpServletRequest request = post("/api/v1/auth/login");
        request.setRemoteAddr("10.0.0.9");
        when(rateLimiter.tryAcquire(eq("ip:10.0.0.9:/api/v1/auth/login"), eq(PER_USER), any(Duration.class)))
                .thenReturn(true);
        when(rateLimiter.tryAcquire(eq("global:/api/v1/auth/login"), eq(GLOBAL), any(Duration.class)))
                .thenReturn(false);

        filter(true).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("X-RateLimit-Scope")).isEqualTo("global");
        assertThat(chain.getRequest()).isNull();
    }
}
