package com.beomsu.becommerce;

import com.beomsu.becommerce.ratelimit.RateLimiter;
import com.beomsu.becommerce.ratelimit.RateLimitFilter;
import com.beomsu.becommerce.auth.HashConcurrencyLimiter;
import com.beomsu.becommerce.auth.AuthController;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import com.beomsu.becommerce.member.Member;
import com.beomsu.becommerce.member.MemberRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.security.web.SecurityFilterChain;

import java.util.Map;

/**
 * 보안 설정.
 *
 * <p>접근 제어:
 * <ul>
 *   <li>{@code POST /api/v1/auth/login} → 개방(로그인, 여기서만 BCrypt 1회 검증 후 JWT 발급)</li>
 *   <li>{@code /api/v1/admin/**} → ROLE_ADMIN (DLQ 재처리 등 운영 액션). 강제취소 maker-checker
 *       시연을 위해 2번째 어드민({@code admin2})을 둔다 — 요청자≠승인자를 강제하려면 서로 다른 어드민이 필요.</li>
 *   <li>{@code /api/v1/orders/**}, {@code /api/v1/payments/confirm} → ROLE_USER 인증.
 *       userId는 인증된 principal에서 얻어 <b>주문 소유권을 검증</b>한다(IDOR 방지).</li>
 *   <li>{@code /api/v1/webhooks/**} → 개방(수신부가 스스로 인증한다. Mock PG 경로는 HMAC 서명이고,
 *       토스 경로는 서명이 없어 <b>페이로드를 믿지 않고 조회 API로 재검증</b>하는 것이 방어선이다.
 *       위조가 만드는 헛조회는 발신 IP 허용 목록으로 좁힐 수 있다 — 기본 off, 프록시 뒤에서는 앞단이 맡는다)</li>
 *   <li>{@code /actuator} → ADMIN. 단 {@code health/info}와 {@code prometheus}(수집기 스크레이프)는 공개</li>
 * </ul>
 *
 * <p>인증은 <b>JWT Bearer(OAuth2 Resource Server, Nimbus HS256 대칭키)</b>로 한다. 무상태 HTTP
 * Basic이 요청마다 BCrypt로 비밀번호를 재검증하던 병목(min ~110ms)을 없애기 위해, BCrypt 검증은
 * 로그인({@link AuthController}) 1회로 밀어내고 이후 요청은 대칭키 서명만 빠르게 검증한다.
 * 토큰 subject에 userId(데모 유저 username "1" 등)를 실어 {@code principal.getName()}이 계속
 * userId를 반환한다 — "userId를 클라이언트가 아니라 인증 컨텍스트에서 얻는다"는 원칙은 그대로다.
 * {@code UserDetailsService}(데모 유저)와 BCrypt 인코더는 로그인 검증에 계속 쓰인다.
 * 기본 자격증명/시크릿은 로컬 개발용 — 운영에서 반드시 오버라이드.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, MeterRegistry meterRegistry,
                                    JwtAuthenticationConverter jwtAuthenticationConverter,
                                    RateLimiter rateLimiter,
                                    @Value("${app.ratelimit.enabled:true}") boolean rateLimitEnabled,
                                    @Value("${app.ratelimit.per-user-per-sec:5}") int perUserPerSec,
                                    @Value("${app.ratelimit.global-per-sec:100}") int globalPerSec)
            throws Exception {
        http
                // 세션 없는 API + HMAC 웹훅이라 CSRF 토큰은 부적합 → 비활성화
                .csrf(csrf -> csrf.disable())
                // JWT 무상태 인증 — 서버 세션을 만들지 않는다
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 로그인(BCrypt 1회)·갱신(refresh 자체가 소유 증명)은 개방,
                        // 로그아웃은 현재 access를 폐기하므로 인증 필요.
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login", "/api/v1/auth/refresh").permitAll()
                        // 회원 가입은 로그인 전에 가능해야 하므로 개방한다(이후 이메일로 /auth/login).
                        .requestMatchers(HttpMethod.POST, "/api/v1/members/signup").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/logout").authenticated()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/webhooks/**").permitAll()      // 수신부가 자체 인증
                        // 카탈로그 조회는 개방한다 — 상품 탐색은 로그인 없이 되어야 한다(쓰기 표면 없음).
                        // anyRequest().permitAll()로도 열리지만, 이건 설계 의도라 명시해 회귀를 막는다.
                        .requestMatchers(HttpMethod.GET, "/api/v1/products/**", "/api/v1/categories").permitAll()
                        .requestMatchers("/api/v1/orders/**", "/api/v1/payments/**").hasRole("USER")
                        .requestMatchers("/api/v1/subscriptions/**").hasRole("USER")   // 구독은 회원 본인 소유
                        .requestMatchers("/api/v1/wallet/**").hasRole("USER")          // 월렛은 회원 본인 소유
                        .requestMatchers("/api/v1/points/**").hasRole("USER")          // 포인트는 회원 본인 소유
                        // 찜은 회원 본인 소유이고 서버에 저장한다(장바구니는 localStorage라 여기 없다).
                        // 개인화 신호로 쓰려면 서버에 남아야 한다 — 정책이 갈리는 근거는 V58 주석 참고.
                        .requestMatchers("/api/v1/wishlist/**").hasRole("USER")
                        // 선착순 대기열: 로그인 사용자만 줄 서기(멤버=인증 principal userId). 결제 경로와는
                        // 결합하지 않는 독립 프리미티브(입장/상태/이탈)이지만 참가자 식별을 위해 인증은 요구한다.
                        .requestMatchers("/api/v1/queue/**").hasRole("USER")
                        // health/info와 Prometheus 스크레이프 엔드포인트는 개방한다. prometheus는
                        // 메트릭 수집기가 Bearer 없이 주기 GET 해야 하므로 인증을 걸면 스크레이프가 401로
                        // 막힌다. 운영에선 management.server.port를 내부망 전용으로 분리해 스크레이프하는
                        // 게 정석이나, 여기선 로컬 Prometheus를 위해 이 엔드포인트만 개방한다.
                        // 나머지 actuator(env·heapdump·modulith 등 정찰 소지)는 계속 ADMIN으로 잠근다.
                        .requestMatchers("/actuator/health", "/actuator/info", "/actuator/prometheus").permitAll()
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        .anyRequest().permitAll())
                // Bearer 토큰의 HS256 서명만 검증(요청당 BCrypt 없음)
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt ->
                        jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)))
                // 유입 제어(rate limit): Bearer 인증 "뒤"에 끼워 principal(userId)로 per-user 키를
                // 만든다. 필터를 빈으로 등록하지 않고 여기서 직접 생성한다 — 빈이면 서블릿 컨테이너가
                // 자동으로 한 번 더 등록해 같은 요청에 이중 적용되기 때문(RateLimitFilter 주석 참고).
                .addFilterAfter(new RateLimitFilter(rateLimiter, rateLimitEnabled, perUserPerSec, globalPerSec,
                                meterRegistry),
                        BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    /**
     * JWT "roles" 클레임 → 권한 매핑. issue 시 "ROLE_USER"/"ROLE_ADMIN"로 저장하므로 prefix 없이
     * 그대로 authority가 되어 {@code hasRole("USER")}/{@code hasRole("ADMIN")}와 맞물린다.
     * principal name은 기본값(sub 클레임) = userId.
     */
    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("");   // roles가 이미 "ROLE_" 접두사를 포함
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    /**
     * 로그인 검증용 AuthenticationManager — 아래 UserDetailsService + BCrypt 인코더로 구성된다.
     * {@link AuthController}가 로그인 시 이 매니저로 BCrypt 검증을 1회 수행한다.
     */
    @Bean
    AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    /**
     * 복합 UserDetailsService — 로그인 식별자로 (a) 먼저 인메모리 데모 계정(admin/admin2/"1"/"2")을
     * 찾고, 없으면 (b) MemberRepository로 이메일 회원을 찾는다. 둘 다 없으면 UsernameNotFoundException.
     *
     * <p><b>숫자 userId 계약 보존</b>: DaoAuthenticationProvider는 인증 성공 시 <i>로드된 UserDetails의
     * getUsername()</i>을 principal 이름으로 삼는다(입력한 로그인 식별자가 아니라). 그래서 회원이
     * 이메일로 로그인해도, 여기서 username을 회원의 숫자 id({@code String.valueOf(member.getId())})로
     * 만들어 반환하면 {@code auth.getName()}=숫자 id가 되고 JWT subject도 숫자로 유지된다. 이 계약이
     * 깨지면 order/payment/wallet/point/subscription의 {@code Long.parseLong(principal.getName())}
     * 소유권 검증이 전부 무너진다.
     *
     * <p>MemberRepository를 생성자(빈 파라미터)로 주입해 지연 없이 조회한다. 인메모리 조회는 로컬이라
     * 빠르고, 회원 조회는 데모 계정이 아닐 때만 1회 DB 히트한다.
     */
    @Bean
    UserDetailsService userDetailsService(
            @Value("${app.admin.username:admin}") String adminUsername,
            @Value("${app.admin.password:admin-local-only}") String adminPassword,
            @Value("${app.user.password:user-local-only}") String userPassword,
            PasswordEncoder encoder,
            MemberRepository memberRepository) {
        UserDetails admin = User.withUsername(adminUsername)
                .password(encoder.encode(adminPassword)).roles("ADMIN").build();
        // maker-checker용 2번째 어드민: 강제취소는 요청자≠승인자를 강제하므로, admin이 요청하고
        // admin2가 승인하는 2인 흐름을 실제로 시연하려면 서로 다른 어드민이 필요하다.
        UserDetails admin2 = User.withUsername("admin2")
                .password(encoder.encode(adminPassword)).roles("ADMIN").build();
        // 데모 사용자: username이 곧 userId (principal.getName() → Long.parseLong)
        UserDetails user1 = User.withUsername("1")
                .password(encoder.encode(userPassword)).roles("USER").build();
        UserDetails user2 = User.withUsername("2")
                .password(encoder.encode(userPassword)).roles("USER").build();
        InMemoryUserDetailsManager inMemory = new InMemoryUserDetailsManager(admin, admin2, user1, user2);

        return username -> {
            // (a) 데모 계정 우선 — admin/admin2/"1"/"2"는 인메모리 그대로 유지(기존 로그인 무중단).
            try {
                return inMemory.loadUserByUsername(username);
            } catch (UsernameNotFoundException notDemo) {
                // (b) 실 회원 — 이메일로 조회. username(=UserDetails.getUsername())을 회원의 숫자 id로
                // 만들어, DaoAuthenticationProvider가 이 값을 principal 이름(=JWT subject)으로 쓰게 한다.
                // 가입 시 이메일을 trim().toLowerCase()로 정규화해 저장하므로, 로그인 조회도 같은 정규화를
                // 적용해야 대소문자·공백이 섞인 입력으로도 로그인된다.
                String normalizedEmail = username == null ? null : username.trim().toLowerCase();
                Member member = memberRepository.findByEmail(normalizedEmail)
                        .orElseThrow(() -> new UsernameNotFoundException("사용자를 찾을 수 없습니다: " + username));
                return User.withUsername(String.valueOf(member.getId()))
                        .password(member.getPasswordHash())
                        .roles(member.getRole())
                        .build();
            }
        };
    }

    /**
     * 비밀번호 인코더 — <b>Argon2id로 인코딩하고, 기존 BCrypt 해시는 계속 검증한다</b>(ADR-009).
     *
     * <p><b>왜 Argon2id인가</b>: OWASP Password Storage Cheat Sheet의 1순위다. BCrypt는 깨진 건
     * 아니지만 그 다음 순위이고, 한계가 둘 있다 — 비밀번호가 <b>72바이트에서 조용히 잘리고</b>,
     * 메모리 하드가 약해(4KB) GPU·ASIC 병렬 공격에 상대적으로 취약하다.
     *
     * <p><b>파라미터</b>: OWASP 최소 권고인 <b>메모리 19MiB · iterations 2 · parallelism 1</b>을 쓴다.
     * Spring이 제공하는 {@code defaultsForSpringSecurity_v5_8()}은 메모리가 16MiB라 권고에 못 미쳐
     * 직접 구성했다.
     *
     * <p><b>기존 해시를 어떻게 하나</b>: {@link DelegatingPasswordEncoder}가 접두사로 알고리즘을 고른다.
     * 새 가입은 {@code {argon2}...}로 저장되고, 이미 저장된 {@code {bcrypt}...}는 맵의 bcrypt 인코더가,
     * 그보다 더 오래된 <b>접두사 없는</b> 해시는 {@code setDefaultPasswordEncoderForMatches}가 검증한다.
     * 그래서 <b>기존 회원이 비밀번호를 재설정하지 않아도 된다.</b> 접두사를 먼저 붙여 둔 덕에 알고리즘
     * 교체가 맵 한 줄과 기본 id 변경으로 끝났다.
     *
     * <p><b>대가</b>: 메모리 하드는 <b>서버 메모리도</b> 쓴다. 로그인 1건마다 19MiB를 잡으므로 동시
     * 로그인 100건이면 순간 약 1.9GB다. 즉 이 교체는 {@code /auth/login}의 <b>DoS 표면을 오히려 키운다.</b>
     * {@link RateLimitFilter}가 그 경로를 IP 기준으로 막는 것만으로는 <b>부족하다</b>. 그건
     * 단위 시간당 시작하는 요청 수를 묶을 뿐이고, 메모리는 <b>같은 순간에 실행 중인 해시 수</b>에
     * 비례한다. 고정 윈도우라 초당 상한이 있어도 매초 초입에 겹칠 수 있다. 그래서
     * {@link HashConcurrencyLimiter}로 동시 실행 수를 따로 묶는다.
     *
     * <p><b>주의</b>: 알고리즘을 바꿔도 로그인 1회 해싱이 남는 사실은 그대로다. 비밀번호를 실제로
     * 대조하는 자리라 없앨 수 없다. JWT가 없앤 것은 <b>요청마다</b> 하던 재검증이지 해싱 자체가 아니다.
     */
    @Bean
    PasswordEncoder passwordEncoder(
            @org.springframework.beans.factory.annotation.Value(
                    "${app.auth.hash-concurrency:8}") int hashConcurrency) {
        String encodingId = "argon2";
        // OWASP 최소 권고: 메모리 19MiB(=19456KB), iterations 2, parallelism 1.
        PasswordEncoder argon2 = new Argon2PasswordEncoder(16, 32, 1, 19456, 2);
        PasswordEncoder bcrypt = new BCryptPasswordEncoder();

        DelegatingPasswordEncoder delegating = new DelegatingPasswordEncoder(
                encodingId, Map.of(encodingId, argon2, "bcrypt", bcrypt));
        // 접두사가 아예 없는 레거시 해시(가장 오래된 것)도 계속 검증한다.
        delegating.setDefaultPasswordEncoderForMatches(bcrypt);

        // 유입 제어(RPS)는 <시작하는 요청 수>를 묶고, 이 제한은 <동시에 잡히는 메모리>를 묶는다.
        // 둘은 다른 자원이라 유입 제어만으로는 순간 메모리 상한이 보장되지 않는다.
        return new HashConcurrencyLimiter(delegating, hashConcurrency);
    }
}
