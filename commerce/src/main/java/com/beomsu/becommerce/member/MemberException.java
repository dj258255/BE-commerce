package com.beomsu.becommerce.member;

import com.beomsu.becommerce.shared.DomainException;

/** 회원 도메인 예외. code는 API 에러 응답의 code 필드로 그대로 노출된다. */
public class MemberException extends DomainException {

    public MemberException(String code, String message) {
        super(code, message);
    }

    /** 이미 가입된 이메일 — 가입 시 중복. */
    public static MemberException emailAlreadyExists(String email) {
        return new MemberException("EMAIL_ALREADY_EXISTS",
                "이미 가입된 이메일입니다: " + email);
    }

    /** 회원을 찾을 수 없음 — 로그인 시 이메일 미존재 등. */
    public static MemberException notFound(String email) {
        return new MemberException("MEMBER_NOT_FOUND",
                "회원을 찾을 수 없습니다: " + email);
    }
}
