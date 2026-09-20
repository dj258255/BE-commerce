package com.beomsu.becommerce.member;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.regex.Pattern;

/**
 * 회원 애플리케이션 서비스 — member 모듈의 공개 진입점.
 *
 * <p>가입({@link #signup})은 이메일 중복을 막고 비밀번호를 BCrypt로 해시해 저장한다(원문은 저장하지
 * 않는다). {@link #loadByEmail}은 인증 배선(복합 UserDetailsService)이 로그인 시 이메일로 회원을 찾을
 * 때 쓰인다 — 여기서 로드된 회원의 <b>숫자 id</b>가 JWT subject(=userId)가 된다.
 */
@Service
@Transactional
public class MemberService {

    /** 아주 느슨한 이메일 형식 방어(로컬@도메인). 엄격 검증은 목적이 아니고 빈값·명백한 오형식만 거른다. */
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    /** 최소 비밀번호 길이 — 너무 짧은 값을 방어한다. */
    private static final int MIN_PASSWORD_LENGTH = 8;

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;

    public MemberService(MemberRepository memberRepository, PasswordEncoder passwordEncoder) {
        this.memberRepository = memberRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * 회원 가입 — 이메일이 이미 있으면 EMAIL_ALREADY_EXISTS, 아니면 BCrypt 해시로 저장하고 뷰를 반환한다.
     * 원문 비밀번호는 저장/노출하지 않는다.
     */
    public MemberView signup(String email, String rawPassword) {
        String normalizedEmail = validateEmail(email);
        validatePassword(rawPassword);
        if (memberRepository.existsByEmail(normalizedEmail)) {
            throw MemberException.emailAlreadyExists(normalizedEmail);
        }
        String passwordHash = passwordEncoder.encode(rawPassword);
        try {
            Member saved = memberRepository.save(Member.of(normalizedEmail, passwordHash));
            return MemberView.from(saved);
        } catch (DataIntegrityViolationException e) {
            // 동시 가입 레이스 — 두 요청이 existsByEmail을 모두 통과해도 uk_members_email 유니크가
            // 두 번째 insert를 막는다. 500이 아니라 의미에 맞는 409(EMAIL_ALREADY_EXISTS)로 변환한다.
            throw MemberException.emailAlreadyExists(normalizedEmail);
        }
    }

    /**
     * 인증용 — 이메일로 회원을 로드한다. 없으면 MEMBER_NOT_FOUND. 복합 UserDetailsService가 로그인 시
     * 호출해, 로드된 회원의 숫자 id를 UserDetails username(=principal, JWT subject)으로 삼는다.
     */
    @Transactional(readOnly = true)
    public Member loadByEmail(String email) {
        return memberRepository.findByEmail(email == null ? null : email.trim().toLowerCase())
                .orElseThrow(() -> MemberException.notFound(email));
    }

    private String validateEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new MemberException("INVALID_EMAIL", "이메일은 필수입니다.");
        }
        String normalized = email.trim().toLowerCase();
        if (!EMAIL_PATTERN.matcher(normalized).matches()) {
            throw new MemberException("INVALID_EMAIL", "이메일 형식이 올바르지 않습니다: " + email);
        }
        return normalized;
    }

    private void validatePassword(String rawPassword) {
        if (rawPassword == null || rawPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new MemberException("INVALID_PASSWORD",
                    "비밀번호는 최소 %d자 이상이어야 합니다.".formatted(MIN_PASSWORD_LENGTH));
        }
    }
}
