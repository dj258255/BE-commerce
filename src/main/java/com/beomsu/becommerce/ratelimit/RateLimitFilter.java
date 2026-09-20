package com.beomsu.becommerce.ratelimit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 쓰기 API 유입 제어 필터 — "감당 못 할 요청은 바깥 층에서 싸게 거절한다(429)".
 *
 * <p>대상은 서버가 실제로 일(DB 쓰기·PG 호출)을 시작하는 두 진입점만이다:
 * {@code POST /api/v1/orders}, {@code POST /api/v1/payments/confirm}(정확 매칭).
 * 한도는 두 겹이다:
 * <ul>
 *   <li><b>per-user</b> — {@code user:{userId}:{path}}: 한 사용자가 초당 수백 번 쳐도
 *       {@code app.ratelimit.per-user-per-sec}(기본 5)까지만 받는다(스크립트/이중클릭 억제).</li>
 *   <li><b>global</b> — {@code global:{path}}: 전체 유입을 {@code app.ratelimit.global-per-sec}
 *       (기본 100)으로 캡 — 사용자가 수만 명이어도 DB로 가는 총량은 상한이 있다.</li>
 * </ul>
 *
 * <p>초과 시 429 + {@code Retry-After: 1}을 <b>필터에서 직접</b> 써서 컨트롤러·서비스·DB 커넥션에
 * 아무 비용도 지우지 않는다. 미인증 요청은 통과시킨다(뒤의 시큐리티가 401로 거절 — principal 없이
 * per-user 키를 만들 수 없고, 익명을 하나의 버킷에 몰면 정상 익명 트래픽까지 오폭한다).
 *
 * <p>이 필터는 <b>@Component가 아니다</b> — SecurityConfig가 시큐리티 체인 안에서
 * {@code BearerTokenAuthenticationFilter} 뒤(인증 확정 후, principal 사용 가능)에 직접 끼운다.
 * 빈으로 두면 서블릿 컨테이너가 자동으로 한 번 더 등록해 이중 적용되므로, 빈 등록 자체를 피했다.
 *
 * <p>{@code app.ratelimit.enabled=false}로 통째로 끌 수 있다 — k6 스파이크를 전/후로 돌려
 * "429로 쳐낸 뒤 성공 요청의 p95가 유지되는가"를 비교하기 위한 스위치.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    /** 정확 매칭 대상 경로 — 서버 최초 쓰기 진입점만. 조회는 제한하지 않는다. */
    private static final Set<String> TARGET_PATHS =
            Set.of("/api/v1/orders", "/api/v1/payments/confirm");

    /** 미인증 진입점 — IP 기준으로 제한한다(브루트포스·BCrypt DoS·가입 스팸 방어). */
    private static final Set<String> AUTH_PATHS =
            Set.of("/api/v1/auth/login", "/api/v1/members/signup");

    private static final Duration WINDOW = Duration.ofSeconds(1);

    /** 429를 낸 층 — 응답 헤더 {@code X-RateLimit-Scope}로 나간다. {@link #reject} 참고. */
    private static final String SCOPE_USER = "user";      // 이 사용자의 초당 한도
    private static final String SCOPE_IP = "ip";          // 이 IP의 초당 한도(미인증 경로)
    private static final String SCOPE_GLOBAL = "global";  // 경로 전체의 초당 한도(시스템 포화)
    private static final String RATE_LIMITED_BODY =
            "{\"code\":\"RATE_LIMITED\",\"message\":\"요청이 너무 잦습니다. 잠시 후 다시 시도해 주세요.\"}";

    private final RateLimiter rateLimiter;
    private final boolean enabled;
    private final int perUserPerSec;
    private final int globalPerSec;
    private final MeterRegistry meterRegistry;

    /** {scope + path} 조합별 카운터 캐시 — 요청마다 레지스트리를 조회하지 않는다. 조합 수는 3×4로 유한하다. */
    private final Map<String, Counter> rejectionCounters = new ConcurrentHashMap<>();

    public RateLimitFilter(RateLimiter rateLimiter, boolean enabled,
                           int perUserPerSec, int globalPerSec, MeterRegistry meterRegistry) {
        this.rateLimiter = rateLimiter;
        this.enabled = enabled;
        this.perUserPerSec = perUserPerSec;
        this.globalPerSec = globalPerSec;
        this.meterRegistry = meterRegistry;

        // 설정된 한도를 지표로 내보낸다. 거절 수만 보면 "많이 쳐낸다"까지만 알 수 있고,
        // 그게 한도가 낮아서인지 부하가 큰지는 한도 값을 같이 봐야 판단된다.
        Gauge.builder("ratelimit.limit.per.user", this, f -> f.perUserPerSec)
                .description("사용자(또는 IP)당 초당 허용치 설정값")
                .register(meterRegistry);
        Gauge.builder("ratelimit.limit.global", this, f -> f.globalPerSec)
                .description("경로당 전체 초당 허용치 설정값")
                .register(meterRegistry);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!enabled || !"POST".equals(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        String path = request.getRequestURI();

        // 미인증 진입점(로그인/회원가입)은 principal이 없으므로 <b>클라이언트 IP</b>로 제한한다.
        // 이 두 경로는 요청마다 BCrypt(검증/인코딩, ~110ms)를 태우고 크리덴셜 브루트포스·가입 스팸의
        // 표적이라, 미인증이라고 그냥 통과시키면 비대칭 DoS·계정 열거에 노출된다.
        if (AUTH_PATHS.contains(path)) {
            String ip = clientIp(request);
            if (!rateLimiter.tryAcquire("ip:" + ip + ":" + path, perUserPerSec, WINDOW)) {
                reject(response, SCOPE_IP, path);
                return;
            }
            if (!rateLimiter.tryAcquire("global:" + path, globalPerSec, WINDOW)) {
                reject(response, SCOPE_GLOBAL, path);
                return;
            }
            chain.doFilter(request, response);
            return;
        }

        if (!TARGET_PATHS.contains(path)) {
            chain.doFilter(request, response);
            return;
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            // 미인증은 여기서 판단하지 않는다 — 뒤의 인가 단계가 401로 거절한다.
            chain.doFilter(request, response);
            return;
        }

        // per-user 먼저(더 좁은 한도) — 초과면 global 카운터는 소비하지 않는다. 한 사람이 스크립트로
        // 때린다고 전체 예산을 태우면, 그 사람 때문에 멀쩡한 다른 사용자가 거절된다.
        if (!rateLimiter.tryAcquire("user:" + auth.getName() + ":" + path, perUserPerSec, WINDOW)) {
            reject(response, SCOPE_USER, path);
            return;
        }
        if (!rateLimiter.tryAcquire("global:" + path, globalPerSec, WINDOW)) {
            reject(response, SCOPE_GLOBAL, path);
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * 클라이언트 IP — <b>소켓 피어({@code getRemoteAddr})만</b> 쓴다. {@code X-Forwarded-For}를 앱에서 직접
     * 읽으면 클라이언트가 헤더를 위조·회전해 per-IP 한도를 우회할 수 있어(스푸핑 가능 필드) 신뢰하지 않는다.
     * 프록시/LB 뒤 배포에서는 {@code server.forward-headers-strategy=FRAMEWORK}(+ Tomcat
     * {@code remoteip.internal-proxies}를 LB CIDR로 제한)로 <b>신뢰된 프록시에서 온 경우에만</b> 프레임워크가
     * 실 클라이언트 IP를 안전하게 대입하게 하고, 여기서는 그 결과인 remoteAddr를 그대로 쓴다.
     */
    private static String clientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }

    /**
     * 429를 필터에서 직접 write — 컨트롤러/서비스/DB에 비용 0.
     *
     * <p>{@code X-RateLimit-Scope}로 <b>어느 층이 잘랐는지</b> 알린다. 같은 429라도 대응이 정반대다 —
     * {@code user}는 그 클라이언트만 물러나면 되고(재시도가 유효), {@code global}은 시스템 전체가
     * 포화라 그 클라이언트가 얌전해도 안 풀린다(증설·원인 규명이 필요). 이 구분이 없으면 대시보드의
     * 429 그래프만 보고 "누가 때리는 중"인지 "우리가 감당을 못 하는 중"인지 가릴 수 없다.
     */
    /**
     * 거절을 층·경로별로 센다.
     *
     * <p><b>왜 필요한가</b>: 이 지표가 없으면 운영에서 한도를 조정할 근거가 생기지 않는다.
     * 얼마나 쳐내는지, 어느 층이 쳐내는지 보이지 않으면 {@code global-per-sec} 같은 값은 처음
     * 정한 짐작에 영원히 머문다 — 실제로 이 프로젝트의 100은 측정 없이 정한 값이었고, 다계정
     * 실측(성능 리포트 9절) 전까지 아무도 그걸 확인할 수 없었다.
     *
     * <p>판독법은 {@code scope} 태그로 갈린다.
     * <ul>
     *   <li>{@code global}이 지속적으로 오르면 — 한도가 실제 처리 능력보다 낮거나, 정말로 포화다.
     *       처리량({@code http_server_requests})이 한도 근처에서 평평하면 포화이고, 한도보다 한참
     *       낮은데도 거절이 나면 한도가 낮게 잡힌 것이다.</li>
     *   <li>{@code user}/{@code ip}만 오르면 — 특정 클라이언트의 남용이다. 한도를 올릴 일이 아니다.</li>
     * </ul>
     */
    private void countRejection(String scope, String path) {
        rejectionCounters.computeIfAbsent(scope + "|" + path, key -> Counter
                .builder("ratelimit.rejected")
                .description("429로 거절한 요청 수. scope로 어느 층이 거절했는지 구분한다")
                .tag("scope", scope)
                .tag("path", path)
                .register(meterRegistry)).increment();
    }

    private void reject(HttpServletResponse response, String scope, String path) throws IOException {
        countRejection(scope, path);
        response.setStatus(429);
        response.setHeader("Retry-After", "1");                    // 고정 윈도우 = 1초 뒤 새 윈도우
        response.setHeader("X-RateLimit-Scope", scope);
        response.setContentType("application/json;charset=UTF-8");
        response.getOutputStream().write(RATE_LIMITED_BODY.getBytes(StandardCharsets.UTF_8));
    }
}
