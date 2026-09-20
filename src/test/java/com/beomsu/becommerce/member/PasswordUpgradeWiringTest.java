package com.beomsu.becommerce.member;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 이관이 <b>실제로 호출되는지</b>를 검증한다(ADR-009).
 *
 * <p>{@link MemberPasswordUpgradeServiceTest}는 서비스가 <i>불렸을 때</i> 무엇을 하는지만 본다.
 * 그건 "서비스가 있다"까지만 증명한다. 이 프로젝트에서 감사 로그 서비스가 만들어져 있었는데
 * 호출부가 없어 아무 일도 하지 않던 적이 있다. 같은 실수를 반복하지 않으려면 <b>호출 경로 자체</b>를
 * 고정해야 한다.
 *
 * <p>그래서 프로덕션과 같은 인코더를 실제 {@link DaoAuthenticationProvider}에 물려, 옛 해시로
 * 로그인했을 때 이관이 <b>자동으로 트리거되는지</b>를 본다. 검증 대상은 두 가지다.
 * ① {@code DelegatingPasswordEncoder.upgradeEncoding}이 옛 접두사에 참을 반환하는가
 * ② provider가 그 판정으로 {@code UserDetailsPasswordService}를 호출하는가
 */
class PasswordUpgradeWiringTest {

    private static final String RAW = "correct-horse-battery";

    /** SecurityConfig와 같은 구성. */
    private static PasswordEncoder productionEncoder() {
        PasswordEncoder argon2 = new Argon2PasswordEncoder(16, 32, 1, 19456, 2);
        PasswordEncoder bcrypt = new BCryptPasswordEncoder();
        DelegatingPasswordEncoder d = new DelegatingPasswordEncoder(
                "argon2", Map.of("argon2", argon2, "bcrypt", bcrypt));
        d.setDefaultPasswordEncoderForMatches(bcrypt);
        return d;
    }

    private static DaoAuthenticationProvider providerWith(String storedHash,
                                                          MemberPasswordUpgradeService upgrades) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setPasswordEncoder(productionEncoder());
        provider.setUserDetailsService(username ->
                User.withUsername("1000").password(storedHash).roles("USER").build());
        provider.setUserDetailsPasswordService(upgrades);
        return provider;
    }

    @Test
    @DisplayName("{bcrypt} 해시로 로그인하면 이관이 자동 호출된다 — 배선이 살아 있다")
    void loginWithBcryptHashTriggersUpgrade() {
        MemberRepository repository = mock(MemberRepository.class);
        Member member = Member.of("a@b.com", "{bcrypt}old");
        when(repository.findById(1000L)).thenReturn(Optional.of(member));
        MemberPasswordUpgradeService upgrades = new MemberPasswordUpgradeService(repository);

        String stored = "{bcrypt}" + new BCryptPasswordEncoder().encode(RAW);
        Authentication result = providerWith(stored, upgrades)
                .authenticate(new UsernamePasswordAuthenticationToken("a@b.com", RAW));

        assertThat(result.isAuthenticated()).isTrue();
        // 옛 해시가 새 알고리즘으로 바뀌어 저장됐다.
        assertThat(member.getPasswordHash()).startsWith("{argon2}");
        verify(repository).saveAndFlush(member);
    }

    @Test
    @DisplayName("접두사 없는 레거시 해시로 로그인해도 이관된다 — 가장 오래된 세대")
    void loginWithUnprefixedLegacyHashTriggersUpgrade() {
        MemberRepository repository = mock(MemberRepository.class);
        Member member = Member.of("a@b.com", "legacy");
        when(repository.findById(1000L)).thenReturn(Optional.of(member));

        String stored = new BCryptPasswordEncoder().encode(RAW);   // 접두사 없음
        providerWith(stored, new MemberPasswordUpgradeService(repository))
                .authenticate(new UsernamePasswordAuthenticationToken("a@b.com", RAW));

        assertThat(member.getPasswordHash()).startsWith("{argon2}");
    }

    @Test
    @DisplayName("이미 {argon2}면 이관을 호출하지 않는다 — 매 로그인마다 쓰지 않는다")
    void loginWithCurrentAlgorithmDoesNotUpgrade() {
        MemberRepository repository = mock(MemberRepository.class);
        MemberPasswordUpgradeService upgrades = spy(new MemberPasswordUpgradeService(repository));

        String stored = productionEncoder().encode(RAW);            // 이미 {argon2}
        providerWith(stored, upgrades)
                .authenticate(new UsernamePasswordAuthenticationToken("a@b.com", RAW));

        verify(upgrades, never()).updatePassword(any(), any());
        verify(repository, never()).saveAndFlush(any());
    }
}
