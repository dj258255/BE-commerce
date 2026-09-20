package com.beomsu.becommerce.auth.internal;

import com.beomsu.becommerce.auth.TokenStore;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 로그인/갱신/로그아웃 토큰 오케스트레이션 — {@link JwtService}(발급)와 {@link TokenStore}(상태)를
 * 엮는다. 컨트롤러는 이 서비스만 호출하고 access/refresh 저장·회전·폐기의 순서는 여기서 책임진다.
 *
 * <p>설계:
 * <ul>
 *   <li><b>access + refresh 분리</b> — 짧은 access(기본 30분, 서명만으로 빠르게 검증)와
 *       긴 refresh(기본 14일, Redis에만 존재)를 함께 발급해 재로그인 없이 세션을 연장한다.</li>
 *   <li><b>refresh 회전(rotation)</b> — 갱신 시 옛 refresh를 즉시 삭제하고 새 것을 발급한다.
 *       한 번 쓴 refresh는 재사용 불가 → 탈취된 refresh의 재사용 공격을 좁힌다.</li>
 *   <li><b>로그아웃 폐기</b> — refresh는 삭제, access는 잔여 만료 시간만큼 denylist에 올린다.</li>
 * </ul>
 */
@Service
public class AuthTokenService {

    private final JwtService jwtService;
    private final TokenStore tokenStore;

    public AuthTokenService(JwtService jwtService, TokenStore tokenStore) {
        this.jwtService = jwtService;
        this.tokenStore = tokenStore;
    }

    /** 로그인 성공 직후: access 발급 + refresh 발급/저장. */
    public TokenPair login(String userId, List<String> roles) {
        String access = jwtService.issueAccess(userId, roles);
        String refreshId = jwtService.newRefreshId();
        tokenStore.saveRefresh(refreshId, userId, String.join(",", roles), jwtService.refreshTtl());
        return new TokenPair(access, refreshId, jwtService.expirySeconds());
    }

    /**
     * refresh 토큰으로 새 access를 발급하고 refresh를 회전한다.
     *
     * @throws AuthException refresh가 없거나 이미 회전·폐기된 경우(401 매핑)
     */
    public TokenPair refresh(String refreshId) {
        TokenStore.RefreshData data = tokenStore.lookupRefresh(refreshId)
                .orElseThrow(() -> new AuthException("유효하지 않은 refresh 토큰입니다."));

        // 회전: 옛 refresh는 즉시 무효화(재사용 방지) 후 새 것을 발급한다.
        tokenStore.deleteRefresh(refreshId);
        String newRefreshId = jwtService.newRefreshId();
        tokenStore.saveRefresh(newRefreshId, data.userId(),
                String.join(",", data.roles()), jwtService.refreshTtl());

        String access = jwtService.issueAccess(data.userId(), data.roles());
        return new TokenPair(access, newRefreshId, jwtService.expirySeconds());
    }

    /**
     * 로그아웃: refresh 삭제 + access denylist 등록.
     *
     * @param jti                access 토큰의 jti(널이면 폐기 skip — 구토큰)
     * @param accessExpEpochSec  access 만료 시각(epoch seconds) — denylist TTL은 잔여 시간만
     * @param refreshId          로그아웃할 refresh(널/공백이면 access만 폐기)
     */
    public void logout(String jti, long accessExpEpochSec, String refreshId) {
        if (refreshId != null && !refreshId.isBlank()) {
            tokenStore.deleteRefresh(refreshId);
        }
        if (jti != null && !jti.isBlank()) {
            long remaining = accessExpEpochSec - Instant.now().getEpochSecond();
            if (remaining > 0) {
                tokenStore.revokeAccess(jti, Duration.ofSeconds(remaining));
            }
        }
    }

    /** 로그인/갱신 응답 묶음. {@code refreshToken}은 opaque refresh id다. */
    public record TokenPair(String accessToken, String refreshToken, long expiresInSeconds) {
    }
}
