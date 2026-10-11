package com.beomsu.becommerce.shared;

/**
 * 도메인 규칙 위반의 기반 예외.
 *
 * <p>각 모듈은 이 예외를 상속해 도메인별 예외를 정의한다. {@code code}는 API 에러 응답의
 * {@code code} 필드로 그대로 노출되며(10-API-스펙 문서), 로그·클라이언트 분기의 키가 된다.
 *
 * <p>물리적으로 media 모듈에 있다(R32, ADR-080) — commerce의 나머지 {@code shared} 패키지
 * (crypto·outbox·Money)는 여전히 commerce 쪽에 있다. media가 commerce에 의존하지 않고도
 * {@link com.beomsu.becommerce.shorts.ShortsException}을 이 타입으로 만들 수 있어야 해서,
 * 패키지 이름은 그대로 두고 물리적 위치만 옮겼다(split package — Java 모듈 시스템을 쓰지 않는
 * 이 프로젝트에서는 합법이고, commerce가 media에 의존하므로 commerce의 다른 모든 모듈은
 * 이 클래스를 전과 똑같이 {@code import com.beomsu.becommerce.shared.DomainException}으로 쓴다).
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
