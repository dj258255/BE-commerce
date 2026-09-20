package com.beomsu.becommerce.auth.internal;

import com.beomsu.becommerce.auth.AuthController;

/**
 * 토큰 갱신/폐기 흐름에서의 인증 실패 — {@link AuthController}에서 401로 매핑된다.
 * (예: 존재하지 않거나 이미 회전·폐기된 refresh 토큰.)
 */
public class AuthException extends RuntimeException {
    public AuthException(String message) {
        super(message);
    }
}
