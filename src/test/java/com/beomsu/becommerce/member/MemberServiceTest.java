package com.beomsu.becommerce.member;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemberServiceTest {

    // 실제 BCryptPasswordEncoder — 해시가 원문과 다르고 matches로 검증 가능한지 실물로 본다.
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private MemberRepository memberRepository;
    private MemberService service;

    @BeforeEach
    void setUp() {
        memberRepository = mock(MemberRepository.class);
        service = new MemberService(memberRepository, passwordEncoder);
    }

    @Test
    @DisplayName("signup: 비밀번호를 BCrypt로 해시 저장하고 회원 뷰를 반환한다(원문·해시 미노출)")
    void signupHashesAndReturnsView() {
        when(memberRepository.existsByEmail("alice@example.com")).thenReturn(false);
        when(memberRepository.save(any())).thenAnswer(inv -> {
            Member m = inv.getArgument(0);
            ReflectionTestUtils.setField(m, "id", 1000L); // DB IDENTITY 흉내
            return m;
        });

        MemberView view = service.signup("alice@example.com", "password123");

        assertThat(view.id()).isEqualTo(1000L);
        assertThat(view.email()).isEqualTo("alice@example.com");
        assertThat(view.role()).isEqualTo("USER");

        ArgumentCaptor<Member> captor = ArgumentCaptor.forClass(Member.class);
        verify(memberRepository).save(captor.capture());
        Member saved = captor.getValue();
        // 원문이 아니라 BCrypt 해시가 저장돼야 하고, matches로 검증 가능해야 한다.
        assertThat(saved.getPasswordHash()).isNotEqualTo("password123");
        assertThat(passwordEncoder.matches("password123", saved.getPasswordHash())).isTrue();
    }

    @Test
    @DisplayName("signup: 이메일은 소문자로 정규화되어 중복검사·저장된다")
    void signupNormalizesEmail() {
        when(memberRepository.existsByEmail("bob@example.com")).thenReturn(false);
        when(memberRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        MemberView view = service.signup("  BOB@Example.com ", "password123");

        assertThat(view.email()).isEqualTo("bob@example.com");
        verify(memberRepository).existsByEmail("bob@example.com");
    }

    @Test
    @DisplayName("signup: 이미 가입된 이메일이면 EMAIL_ALREADY_EXISTS, 저장하지 않는다")
    void signupDuplicateRejected() {
        when(memberRepository.existsByEmail("dup@example.com")).thenReturn(true);

        assertThatThrownBy(() -> service.signup("dup@example.com", "password123"))
                .isInstanceOf(MemberException.class)
                .satisfies(e -> assertThat(((MemberException) e).code()).isEqualTo("EMAIL_ALREADY_EXISTS"));

        verify(memberRepository, never()).save(any());
    }

    @Test
    @DisplayName("signup: 잘못된 이메일 형식이면 INVALID_EMAIL")
    void signupInvalidEmail() {
        assertThatThrownBy(() -> service.signup("not-an-email", "password123"))
                .isInstanceOf(MemberException.class)
                .satisfies(e -> assertThat(((MemberException) e).code()).isEqualTo("INVALID_EMAIL"));
        verify(memberRepository, never()).save(any());
    }

    @Test
    @DisplayName("signup: 너무 짧은 비밀번호는 INVALID_PASSWORD")
    void signupShortPassword() {
        assertThatThrownBy(() -> service.signup("alice@example.com", "short"))
                .isInstanceOf(MemberException.class)
                .satisfies(e -> assertThat(((MemberException) e).code()).isEqualTo("INVALID_PASSWORD"));
        verify(memberRepository, never()).save(any());
    }

    @Test
    @DisplayName("loadByEmail: 이메일로 회원을 로드한다(정규화 후 조회)")
    void loadByEmailFound() {
        Member member = Member.of("carol@example.com", "hash");
        when(memberRepository.findByEmail("carol@example.com")).thenReturn(Optional.of(member));

        Member loaded = service.loadByEmail("Carol@Example.com");

        assertThat(loaded).isSameAs(member);
    }

    @Test
    @DisplayName("loadByEmail: 없으면 MEMBER_NOT_FOUND")
    void loadByEmailNotFound() {
        when(memberRepository.findByEmail(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.loadByEmail("ghost@example.com"))
                .isInstanceOf(MemberException.class)
                .satisfies(e -> assertThat(((MemberException) e).code()).isEqualTo("MEMBER_NOT_FOUND"));
    }
}
