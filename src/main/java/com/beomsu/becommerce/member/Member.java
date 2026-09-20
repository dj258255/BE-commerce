package com.beomsu.becommerce.member;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 회원 애그리거트 — 이메일 기반 실 회원.
 *
 * <p>{@code passwordHash}는 BCrypt 해시만 저장한다(원문 비밀번호는 절대 저장/노출하지 않는다).
 * {@code id}는 인증 후 principal(=userId)로 쓰이므로, 이 숫자 id가 곧 다른 도메인의 소유권 키가 된다.
 * 인메모리 데모 유저 "1"/"2"와 principal이 충돌하지 않도록 members 테이블은 AUTO_INCREMENT=1000에서
 * 시작한다(V14 마이그레이션 주석 참고).
 */
@Entity
@Table(name = "members")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 255)
    private String email;

    /**
     * 알고리즘 접두사가 붙은 해시 — 원문 비밀번호는 저장하지 않는다.
     *
     * <p>{@code {argon2}...} 105자, {@code {bcrypt}...} 68자다. 파라미터를 올리면 더 길어지므로
     * 255로 잡아 다음 튜닝 때 마이그레이션을 다시 쓰지 않게 한다(ADR-009, V23).
     */
    @Column(nullable = false, length = 255)
    private String passwordHash;

    /**
     * 로그인 성공 시 더 강한 알고리즘으로 재인코딩된 해시를 받는다(ADR-009).
     *
     * <p>원문 비밀번호는 <b>로그인 순간에만</b> 존재한다. 그래서 그때가 아니면 기존 해시를 옮길 수 없고,
     * 옮기지 못하면 기존 회원은 영원히 옛 알고리즘에 남는다. 인자는 이미 인코딩된 값이며
     * (Spring이 {@code {argon2}} 접두사까지 붙여 넘긴다) 이 메서드는 원문을 보지 않는다.
     */
    public void replacePasswordHash(String newHash) {
        if (newHash == null || newHash.isBlank()) {
            throw new IllegalArgumentException("해시는 비어 있을 수 없습니다.");
        }
        this.passwordHash = newHash;
    }

    /** 권한(기본 "USER"). JWT roles·hasRole과 맞물린다. */
    @Column(nullable = false, length = 20)
    private String role;

    @Column(nullable = false)
    private Instant createdAt;

    private Member(String email, String passwordHash, String role) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
        this.createdAt = Instant.now();
    }

    /** 신규 회원 생성 — 기본 권한 USER. passwordHash는 이미 BCrypt로 해시된 값이어야 한다. */
    public static Member of(String email, String passwordHash) {
        return new Member(email, passwordHash, "USER");
    }
}
