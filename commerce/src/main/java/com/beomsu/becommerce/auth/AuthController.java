package com.beomsu.becommerce.auth;

import com.beomsu.becommerce.auth.internal.AuthTokenService;
import com.beomsu.becommerce.auth.internal.AuthException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 로그인 → JWT 발급, 그리고 세션 연장(refresh)·종료(logout) 엔드포인트.
 *
 * <p>여기서만 BCrypt로 자격증명을 1회 검증하고(무상태 HTTP Basic의 요청당 재해싱 병목 제거),
 * 성공 시 subject=userId인 access JWT와 opaque refresh 토큰을 함께 발급한다. 이후 보호
 * 엔드포인트는 Bearer access의 서명·폐기만 검증하므로 빠르다. subject를 username(=userId)으로
 * 유지해 {@code principal.getName()} 기반 소유권 검증이 그대로 동작한다.
 *
 * <p><b>하위호환:</b> {@code LoginResponse.token}은 계속 access 토큰이다(기존 클라이언트·부하
 * 스크립트가 {@code .token}을 읽는다). {@code refreshToken} 필드만 추가됐다.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final AuthTokenService authTokenService;

    public AuthController(AuthenticationManager authenticationManager, AuthTokenService authTokenService) {
        this.authenticationManager = authenticationManager;
        this.authTokenService = authTokenService;
    }

    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest request) {
        // BCrypt 검증은 이 1회뿐 — 실패 시 BadCredentialsException → 아래 핸들러가 401.
        Authentication auth = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.username(), request.password()));

        List<String> roles = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)   // "ROLE_USER" / "ROLE_ADMIN"
                .toList();

        AuthTokenService.TokenPair pair = authTokenService.login(auth.getName(), roles);
        return LoginResponse.from(pair);
    }

    /**
     * refresh 토큰으로 새 access를 발급한다(회전). 무효/폐기된 refresh면 {@link AuthException} → 401.
     * 인증 불필요(permitAll) — refresh 자체가 소유 증명이다.
     */
    @PostMapping("/refresh")
    public LoginResponse refresh(@RequestBody RefreshRequest request) {
        AuthTokenService.TokenPair pair = authTokenService.refresh(request.refreshToken());
        return LoginResponse.from(pair);
    }

    /**
     * 로그아웃 — 현재 access를 denylist로 폐기하고 refresh(옵션)를 삭제한다. 인증된 요청만
     * 도달하므로 {@code jwt}는 널이 아니다. jti/exp는 현재 access 토큰에서 읽는다.
     */
    @PostMapping("/logout")
    public Map<String, Boolean> logout(@AuthenticationPrincipal Jwt jwt,
                                       @RequestBody(required = false) LogoutRequest request) {
        String refreshToken = request == null ? null : request.refreshToken();
        long expEpoch = jwt.getExpiresAt() == null ? 0L : jwt.getExpiresAt().getEpochSecond();
        authTokenService.logout(jwt.getId(), expEpoch, refreshToken);
        return Map.of("loggedOut", true);
    }

    /**
     * 컨트롤러 내부에서 던져진 인증 예외는 시큐리티 필터가 감싸지 못하므로 여기서 401로 변환한다
     * (변환하지 않으면 500이 된다). 로그인 실패({@link AuthenticationException})와 refresh 실패
     * ({@link AuthException}) 모두 401로 통일한다.
     */
    @ExceptionHandler({AuthenticationException.class, AuthException.class})
    public ResponseEntity<Map<String, String>> handleAuthFailure(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("code", "AUTHENTICATION_FAILED", "message", "자격 증명이 올바르지 않습니다."));
    }

    public record LoginRequest(String username, String password) {
    }

    public record RefreshRequest(String refreshToken) {
    }

    public record LogoutRequest(String refreshToken) {
    }

    /**
     * {@code token}은 access 토큰(하위호환 — 기존 클라이언트가 읽는 필드), {@code refreshToken}은
     * 추가된 opaque refresh 토큰.
     */
    public record LoginResponse(String token, String refreshToken, String tokenType, long expiresInSeconds) {
        static LoginResponse from(AuthTokenService.TokenPair pair) {
            return new LoginResponse(pair.accessToken(), pair.refreshToken(), "Bearer", pair.expiresInSeconds());
        }
    }
}
