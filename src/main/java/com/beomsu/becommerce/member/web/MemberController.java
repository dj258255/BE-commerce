package com.beomsu.becommerce.member.web;

import com.beomsu.becommerce.member.MemberService;
import com.beomsu.becommerce.member.MemberView;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 회원 REST 컨트롤러 — member 모듈의 사용자 표면.
 *
 * <p>가입은 로그인 전에 가능해야 하므로 SecurityConfig에서 {@code POST /api/v1/members/signup}을
 * permitAll 한다. 로그인은 이메일로 {@code POST /api/v1/auth/login}에 태우면 되고(복합
 * UserDetailsService가 이메일→회원 조회), 발급되는 JWT subject는 회원의 숫자 id다.
 */
@RestController
@RequestMapping("/api/v1/members")
public class MemberController {

    private final MemberService memberService;

    public MemberController(MemberService memberService) {
        this.memberService = memberService;
    }

    /** 회원 가입 — 이메일+비밀번호로 가입하고 201 + 회원 뷰(비밀번호 해시 미노출)를 반환한다. */
    @PostMapping("/signup")
    public ResponseEntity<MemberView> signup(@RequestBody SignupRequest request) {
        MemberView view = memberService.signup(request.email(), request.password());
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    /** 가입 요청 — 이메일과 원문 비밀번호(서버에서 BCrypt 해시). */
    public record SignupRequest(String email, String password) {
    }
}
