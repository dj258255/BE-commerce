package com.beomsu.becommerce.personalization.internal;

import com.beomsu.becommerce.shared.DomainException;

/**
 * 개인화 도메인 예외. code는 10-API-스펙 문서의 에러 코드 체계와 일치한다.
 *
 * <p>새 코드를 만들지 않는다 — 입력 검증 실패는 이 저장소의 공통 코드 {@code INVALID_REQUEST}(400)를
 * 쓴다. 도메인이 하나뿐인데 전용 코드를 늘리면 클라이언트가 분기할 근거만 늘고 얻는 것이 없다.
 */
public class PersonalizationException extends DomainException {

    public PersonalizationException(String code, String message) {
        super(code, message);
    }

    public static PersonalizationException invalidRequest(String message) {
        return new PersonalizationException("INVALID_REQUEST", message);
    }
}
