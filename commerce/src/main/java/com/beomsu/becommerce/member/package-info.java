/**
 * 회원(member) 모듈 — JPA 기반 회원 가입/로그인.
 *
 * <p>기존 인증은 {@code InMemoryUserDetailsManager}의 데모 계정(admin/admin2/"1"/"2")뿐이라 실제
 * 가입한 회원이 없었다. 이 모듈은 이메일로 가입·로그인하는 실 회원을 담는다. 다만 시스템 전체가
 * {@code Long.parseLong(principal.getName())}으로 userId를 <b>숫자</b>로 다뤄 소유권을 검증하므로
 * (order/payment/wallet/point/subscription), 로그인 후 JWT subject는 반드시 <b>숫자 회원 id</b>여야 한다.
 * 그래서 회원은 이메일로 로그인하되, 인증 시 로드되는 UserDetails의 username은 회원의 숫자 id로 만든다
 * (배선은 루트의 {@code SecurityConfig} 복합 UserDetailsService 참고).
 *
 * <p>shared(값 객체·예외)만 참조한다 — 회원은 독립적으로 서고 다른 도메인에 의존하지 않는다.
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = { "shared" }
)
package com.beomsu.becommerce.member;

import com.beomsu.becommerce.SecurityConfig;
