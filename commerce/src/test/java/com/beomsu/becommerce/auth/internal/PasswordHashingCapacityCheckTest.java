package com.beomsu.becommerce.auth.internal;

import com.beomsu.becommerce.auth.internal.PasswordHashingCapacityCheck;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 기동 경고의 계산식을 고정한다 (ADR-009).
 *
 * <p>실제 경고 발화는 힙 상한에 의존해 테스트 환경에서 결정적으로 재현하기 어렵다. 대신 이 검증이
 * 지키려는 것은 <b>계수가 실측과 어긋나지 않는 것</b>이다 — 누가 Argon2 memory 파라미터를 바꾸면
 * 여기서 먼저 깨져서, 경고가 조용히 틀린 숫자를 말하는 상태를 막는다.
 */
class PasswordHashingCapacityCheckTest {

    @Test
    @DisplayName("해시 1건당 메모리 계수가 Argon2 memory 파라미터와 정합한다")
    void perHashMemoryMatchesArgon2Parameter() {
        double parameterMib = PasswordHashingCapacityCheck.ARGON2_MEMORY_KIB / 1024.0;   // 19.0

        // 실측 계수는 파라미터보다 커야 한다(부대 할당 포함). 다만 크게 벗어나면 둘 중 하나가
        // 바뀐 것이므로 알아야 한다 — 파라미터의 1.0~1.2배 안에 있어야 한다.
        assertThat(PasswordHashingCapacityCheck.MEBIBYTES_PER_HASH)
                .isBetween(parameterMib, parameterMib * 1.2);
    }

    @Test
    @DisplayName("Tomcat 기본 스레드(100)의 최악 소요는 2GB에 가깝다 — 실측 1,984MB와 일치")
    void worstCaseForDefaultThreadsIsAboutTwoGigabytes() {
        long worstCaseMib = Math.round(100 * PasswordHashingCapacityCheck.MEBIBYTES_PER_HASH);

        // 힙 2GB에서 동시 100건이 1,984MB를 쓰고 통과했고, 1GB에서는 12건이 죽었다.
        assertThat(worstCaseMib).isEqualTo(1984);
    }
}
