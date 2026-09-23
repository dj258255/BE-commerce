package com.beomsu.becommerce;

import com.beomsu.becommerce.member.Member;
import com.beomsu.becommerce.member.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 복합 UserDetailsService 배선 검증 — 데모 계정은 인메모리로, 회원은 이메일로 조회하되 <b>숫자 id</b>를
 * username(=principal, JWT subject)으로 반환하는지 본다. 이 숫자 계약이 전 모듈 소유권 검증의 전제다.
 */
class CompositeUserDetailsServiceTest {

    private final PasswordEncoder encoder = new BCryptPasswordEncoder();
    private MemberRepository memberRepository;
    private UserDetailsService uds;

    @BeforeEach
    void setUp() {
        memberRepository = mock(MemberRepository.class);
        uds = new SecurityConfig().userDetailsService(
                "admin", "admin-pw", "user-pw", encoder, memberRepository);
    }

    private static boolean hasRole(UserDetails u, String role) {
        return u.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .anyMatch(a -> a.equals("ROLE_" + role));
    }

    @Test
    @DisplayName("인메모리 admin은 그대로 resolve(ROLE_ADMIN), DB 조회 없음")
    void resolvesInMemoryAdmin() {
        UserDetails admin = uds.loadUserByUsername("admin");

        assertThat(admin.getUsername()).isEqualTo("admin");
        assertThat(hasRole(admin, "ADMIN")).isTrue();
    }

    @Test
    @DisplayName("인메모리 데모 유저 \"1\"은 username이 그대로 \"1\"(=userId)로 resolve")
    void resolvesInMemoryDemoUser() {
        UserDetails user = uds.loadUserByUsername("1");

        assertThat(user.getUsername()).isEqualTo("1");
        assertThat(hasRole(user, "USER")).isTrue();
    }

    @Test
    @DisplayName("회원은 이메일로 조회되지만 username은 숫자 id로 resolve(JWT subject 숫자 계약 보존)")
    void resolvesMemberAsNumericId() {
        Member member = Member.of("member@example.com", encoder.encode("password123"));
        ReflectionTestUtils.setField(member, "id", 1000L);
        when(memberRepository.findByEmail("member@example.com")).thenReturn(Optional.of(member));

        UserDetails loaded = uds.loadUserByUsername("member@example.com");

        // 핵심: 이메일로 로그인해도 principal(username)은 숫자 id → Long.parseLong 소유권 검증 유지
        assertThat(loaded.getUsername()).isEqualTo("1000");
        assertThat(hasRole(loaded, "USER")).isTrue();
        assertThat(encoder.matches("password123", loaded.getPassword())).isTrue();
    }

    @Test
    @DisplayName("데모 계정도 회원도 아니면 UsernameNotFoundException")
    void unknownThrows() {
        when(memberRepository.findByEmail(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> uds.loadUserByUsername("ghost@example.com"))
                .isInstanceOf(UsernameNotFoundException.class);
    }
}
