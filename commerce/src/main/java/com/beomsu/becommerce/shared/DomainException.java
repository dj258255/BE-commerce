package com.beomsu.becommerce.shared;

/**
 * 도메인 규칙 위반의 기반 예외.
 *
 * <p>각 모듈은 이 예외를 상속해 도메인별 예외를 정의한다. {@code code}는 API 에러 응답의
 * {@code code} 필드로 그대로 노출되며(10-API-스펙 문서), 로그·클라이언트 분기의 키가 된다.
 */
public abstract class DomainException extends RuntimeException {

    private final String code;

    protected DomainException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
