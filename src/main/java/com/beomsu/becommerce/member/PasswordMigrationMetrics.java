package com.beomsu.becommerce.member;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 비밀번호 해시 이관 진행 게이지 (ADR-009).
 *
 * <p><b>왜 지표가 필요한가</b>: 해시 이관은 <b>끝을 코드로 강제할 수 없다.</b> 원문 비밀번호가 로그인
 * 순간에만 존재하므로, 로그인하지 않는 계정은 옛 해시로 남는다. 남은 방법은 레거시 인코더를 제거해
 * 강제로 재설정을 유도하는 것인데, <b>언제 그래도 되는지</b>를 알아야 한다. 그 판정을 사람의 기억이
 * 아니라 이 값으로 한다.
 *
 * <p>이 값이 <b>0이 되면</b> 레거시 BCrypt 인코더와 {@code setDefaultPasswordEncoderForMatches}를
 * 제거할 수 있다. 그 시점이 이관의 진짜 종료다.
 *
 * <p>미확정 결제의 최장 방치 시간을 게이지로 올린 것과 같은 생각이다 — <b>상태를 만들었으면 그 상태의
 * 재고를 본다.</b> 재고가 안 보이면 줄고 있는지 늘고 있는지 알 수 없다.
 */
@Component
public class PasswordMigrationMetrics {

    /** 현재 인코딩 id — SecurityConfig의 encodingId와 같아야 한다. */
    static final String CURRENT_ENCODING_ID = "argon2";

    private final MemberRepository memberRepository;

    public PasswordMigrationMetrics(MeterRegistry meterRegistry, MemberRepository memberRepository) {
        this.memberRepository = memberRepository;
        Gauge.builder("password.hash.legacy.count", this, PasswordMigrationMetrics::legacyHashCount)
                .description("현재 알고리즘(" + CURRENT_ENCODING_ID + ")으로 옮겨지지 않은 비밀번호 해시 수. "
                        + "0이 되면 레거시 인코더를 제거할 수 있다")
                .register(meterRegistry);
    }

    /** 스크레이프마다 집계 쿼리 1회. 회원 수 규모에서는 무시할 비용이다. */
    double legacyHashCount() {
        return memberRepository.countHashesNotEncodedWith(CURRENT_ENCODING_ID);
    }
}
