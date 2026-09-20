package com.beomsu.becommerce.member;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 로그인 시 해시 이관의 계약을 고정한다(ADR-009).
 *
 * <p>핵심은 <b>이관 실패가 로그인을 막지 않는다</b>는 것이다. 이관은 편의이고 인증은 기능이라,
 * 회원을 못 찾거나 데모 계정이어도 사용자는 그대로 로그인돼야 한다.
 */
class MemberPasswordUpgradeServiceTest {

    private MemberRepository repository;
    private MemberPasswordUpgradeService service;

    private static final String NEW_HASH = "{argon2}$argon2id$v=19$m=19456,t=2,p=1$salt$hash";

    @BeforeEach
    void setUp() {
        repository = mock(MemberRepository.class);
        service = new MemberPasswordUpgradeService(repository);
    }

    private static UserDetails principal(String username) {
        return User.withUsername(username).password("{bcrypt}$2a$10$old").roles("USER").build();
    }

    @Test
    @DisplayName("회원이면 해시를 새 값으로 바꾸고 명시 영속한다")
    void upgradesStoredHash() {
        Member member = Member.of("a@b.com", "{bcrypt}$2a$10$old");
        when(repository.findById(1000L)).thenReturn(Optional.of(member));

        UserDetails result = service.updatePassword(principal("1000"), NEW_HASH);

        assertThat(member.getPasswordHash()).isEqualTo(NEW_HASH);
        assertThat(result.getPassword()).isEqualTo(NEW_HASH);
        // dirty-check 자동 flush를 신뢰하지 않는다 — 상태 확정을 강제한다.
        verify(repository).saveAndFlush(member);
    }

    @Test
    @DisplayName("데모 계정(숫자 아닌 principal)은 건너뛴다 — DB에 없다")
    void skipsNonNumericPrincipal() {
        UserDetails admin = principal("admin");

        UserDetails result = service.updatePassword(admin, NEW_HASH);

        assertThat(result).isSameAs(admin);
        verify(repository, never()).findById(any());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("회원을 못 찾아도 로그인은 통과시킨다 — 이관은 편의, 인증은 기능")
    void doesNotBlockLoginWhenMemberMissing() {
        when(repository.findById(9999L)).thenReturn(Optional.empty());
        UserDetails user = principal("9999");

        UserDetails result = service.updatePassword(user, NEW_HASH);

        assertThat(result).isSameAs(user);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("빈 해시는 거부한다 — 잘못된 값으로 덮어써 계정이 잠기는 것을 막는다")
    void rejectsBlankHash() {
        Member member = Member.of("a@b.com", "{bcrypt}$2a$10$old");

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> member.replacePasswordHash("  "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(member.getPasswordHash()).isEqualTo("{bcrypt}$2a$10$old");
    }
}
