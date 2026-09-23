package com.beomsu.becommerce.member;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsPasswordService;
import org.springframework.stereotype.Service;

/**
 * 로그인 성공 시 옛 알고리즘의 해시를 현재 알고리즘으로 재인코딩한다(ADR-009).
 *
 * <p><b>왜 로그인 순간인가</b>: 원문 비밀번호는 그때만 존재한다. 저장된 건 해시뿐이라 배치로는 옮길 수
 * 없다. 이 자리를 놓치면 기존 회원은 <b>비밀번호를 재설정하기 전까지 영원히 옛 알고리즘</b>에 남고,
 * BCrypt의 72바이트 절단 같은 한계도 그대로 따라온다.
 *
 * <p><b>Spring이 알아서 부른다</b>: {@code DaoAuthenticationProvider}가 인증 성공 후
 * {@code PasswordEncoder.upgradeEncoding(저장된해시)}를 묻고, 참이면 이 서비스의
 * {@link #updatePassword}를 <b>이미 새 알고리즘으로 인코딩된 값</b>과 함께 호출한다. 이 클래스는
 * 원문 비밀번호를 보지 않는다. {@code DelegatingPasswordEncoder}는 저장된 접두사가 현재 인코딩 id와
 * 다르면 업그레이드가 필요하다고 답하므로, {@code {bcrypt}}와 접두사 없는 레거시가 모두 대상이 된다.
 *
 * <p><b>왜 프레임워크 기본 구현이 없나</b>: Spring의 {@code JdbcUserDetailsManager}는 이 인터페이스를
 * 일부러 구현하지 않는다. 자동 갱신이 기존 운영 코드를 깨뜨릴 수 있어 <b>옵트인</b>으로 남겨 둔 것이다.
 * 그래서 직접 구현한다.
 *
 * <p><b>데모 계정은 건너뛴다</b>: 인메모리 계정(admin/admin2/"1"/"2")은 DB에 없다. 이들은 기동 시 현재
 * 인코더로 만들어져 업그레이드 대상이 아니지만, 방어적으로 조회 실패를 조용히 흘려 <b>비밀번호 갱신
 * 실패가 로그인 자체를 막지 않게</b> 한다. 이관은 편의이고 인증은 기능이다.
 */
@Service
public class MemberPasswordUpgradeService implements UserDetailsPasswordService {

    private static final Logger log = LoggerFactory.getLogger(MemberPasswordUpgradeService.class);

    private final MemberRepository memberRepository;
    private final PasswordMigrationMetrics metrics;

    public MemberPasswordUpgradeService(MemberRepository memberRepository, PasswordMigrationMetrics metrics) {
        this.memberRepository = memberRepository;
        this.metrics = metrics;
    }

    /**
     * @param user        인증에 성공한 사용자. {@code getUsername()}은 회원의 숫자 id다
     *                    (SecurityConfig의 UserDetailsService가 그렇게 만든다).
     * @param newPassword <b>이미 인코딩된</b> 새 해시(접두사 포함).
     */
    @Override
    public UserDetails updatePassword(UserDetails user, String newPassword) {
        long memberId;
        try {
            memberId = Long.parseLong(user.getUsername());
        } catch (NumberFormatException notNumericPrincipal) {
            return user;   // 데모 계정(admin 등) — DB에 없다
        }

        return memberRepository.findById(memberId)
                .map(member -> upgrade(user, member, newPassword))
                .orElse(user);   // 회원이 없다 — 이관을 포기하되 로그인은 통과시킨다
    }

    /**
     * 이관 한 건. <b>실패해도 로그인을 막지 않는다.</b>
     *
     * <p>그래서 이 메서드에는 {@code @Transactional}을 두지 않는다. 호출한 쪽 트랜잭션 안에서 쓰기를
     * 하면 예외를 잡아도 그 트랜잭션이 rollback-only 로 낙인찍혀 커밋이 실패한다(pay-26 교훈).
     * <b>쓰기가 자기 트랜잭션에서 끝나야</b> 실패를 삼킬 수 있다. 조회와 쓰기가 한 트랜잭션이어야 할
     * 불변식은 없다 — 쓰기는 해시 한 컬럼을 새 값으로 확정하는 것뿐이다.
     */
    private UserDetails upgrade(UserDetails user, Member member, String newPassword) {
        try {
            member.replacePasswordHash(newPassword);
            // 명시 영속 — readOnly 조회가 세션 FlushMode를 MANUAL로 바꾼 뒤라면 dirty-check
            // 자동 flush를 신뢰할 수 없다(pay-26 교훈).
            memberRepository.saveAndFlush(member);
        } catch (RuntimeException failure) {
            // 삼키되 조용히는 아니다 — 세어서 "왜 레거시 해시가 줄지 않는지"를 답할 수 있게 한다(ADR-009).
            metrics.recordFailed();
            log.warn("비밀번호 해시 이관 실패 memberId={} — 로그인은 통과시킨다", member.getId(), failure);
            return user;
        }
        metrics.recordUpgraded();
        log.info("비밀번호 해시 이관 memberId={}", member.getId());
        return withPassword(user, newPassword);
    }

    private static UserDetails withPassword(UserDetails user, String newPassword) {
        return User.withUserDetails(user).password(newPassword).build();
    }
}
