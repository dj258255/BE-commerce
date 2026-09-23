package com.beomsu.becommerce.auth.internal;

import com.beomsu.becommerce.auth.TokenStore;
import com.beomsu.becommerce.auth.internal.JwtService;
import com.beomsu.becommerce.auth.internal.AuthTokenService;
import com.beomsu.becommerce.auth.internal.AuthException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 로그인/갱신/로그아웃 오케스트레이션 단위 테스트 — JwtService/TokenStore는 목.
 * Redis·서명 실동작 없이 "무엇을 어떤 순서로 저장/폐기하는가"만 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class AuthTokenServiceTest {

    @Mock
    JwtService jwtService;
    @Mock
    TokenStore tokenStore;
    @InjectMocks
    AuthTokenService service;

    @Test
    @DisplayName("login: access 발급 + refresh 저장, TokenPair 반환")
    void login() {
        when(jwtService.issueAccess(eq("1"), any())).thenReturn("access-1");
        when(jwtService.newRefreshId()).thenReturn("refresh-1");
        when(jwtService.refreshTtl()).thenReturn(Duration.ofDays(14));
        when(jwtService.expirySeconds()).thenReturn(1800L);

        AuthTokenService.TokenPair pair = service.login("1", List.of("ROLE_USER"));

        assertThat(pair.accessToken()).isEqualTo("access-1");
        assertThat(pair.refreshToken()).isEqualTo("refresh-1");
        assertThat(pair.expiresInSeconds()).isEqualTo(1800L);
        verify(tokenStore).saveRefresh("refresh-1", "1", "ROLE_USER", Duration.ofDays(14));
    }

    @Test
    @DisplayName("refresh: 회전 — 옛 refresh 삭제 + 새 refresh 저장 + 새 access 발급")
    void refreshRotates() {
        when(tokenStore.lookupRefresh("old"))
                .thenReturn(Optional.of(new TokenStore.RefreshData("1", List.of("ROLE_USER"))));
        when(jwtService.newRefreshId()).thenReturn("new");
        when(jwtService.refreshTtl()).thenReturn(Duration.ofDays(14));
        when(jwtService.issueAccess(eq("1"), any())).thenReturn("access-2");
        when(jwtService.expirySeconds()).thenReturn(1800L);

        AuthTokenService.TokenPair pair = service.refresh("old");

        assertThat(pair.accessToken()).isEqualTo("access-2");
        assertThat(pair.refreshToken()).isEqualTo("new");
        verify(tokenStore).deleteRefresh("old");                                    // 옛 것 폐기
        verify(tokenStore).saveRefresh("new", "1", "ROLE_USER", Duration.ofDays(14)); // 새 것 저장
    }

    @Test
    @DisplayName("refresh: 존재하지 않는 토큰 → AuthException, 아무것도 저장/삭제하지 않음")
    void refreshUnknownThrows() {
        when(tokenStore.lookupRefresh("gone")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.refresh("gone"))
                .isInstanceOf(AuthException.class);

        verify(tokenStore, never()).deleteRefresh(any());
        verify(tokenStore, never()).saveRefresh(any(), any(), any(), any());
    }

    @Test
    @DisplayName("logout: refresh 삭제 + access denylist(잔여 시간 TTL) 등록")
    void logout() {
        long expEpoch = java.time.Instant.now().getEpochSecond() + 600;  // 10분 남음

        service.logout("jti-1", expEpoch, "refresh-1");

        verify(tokenStore).deleteRefresh("refresh-1");
        verify(tokenStore).revokeAccess(eq("jti-1"), any(Duration.class));
    }

    @Test
    @DisplayName("logout: 이미 만료된 access(잔여 음수)는 denylist에 올리지 않는다")
    void logoutSkipsExpiredAccess() {
        long expEpoch = java.time.Instant.now().getEpochSecond() - 10;   // 이미 만료

        service.logout("jti-1", expEpoch, null);

        verify(tokenStore, never()).revokeAccess(any(), any());
        verify(tokenStore, never()).deleteRefresh(any());
    }
}
