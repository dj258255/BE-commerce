package com.beomsu.becommerce.member;

/**
 * 회원 조회 뷰 — 엔티티 대신 노출한다. passwordHash(민감값)는 절대 싣지 않는다.
 */
public record MemberView(Long id, String email, String role) {

    static MemberView from(Member member) {
        return new MemberView(member.getId(), member.getEmail(), member.getRole());
    }
}
