package com.beomsu.becommerce.auth;

import com.beomsu.becommerce.auth.internal.PasswordHashingCapacityCheck;
import com.beomsu.becommerce.auth.internal.HashCapacityExceededException;
import com.beomsu.becommerce.SecurityConfig;
import com.beomsu.becommerce.ratelimit.RateLimitFilter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * Argon2id 해싱의 <b>동시 실행 수</b>를 묶는 데코레이터 (ADR-009).
 *
 * <p><b>왜 유입 제어로는 부족한가</b>: {@link RateLimitFilter}가 제한하는 것은
 * <b>단위 시간당 시작하는 요청 수</b>다. 그런데 Argon2가 잡는 메모리는
 * <b>같은 순간에 실행 중인 해시 수</b>에 비례한다. 둘은 다른 자원이다.
 *
 * <p>고정 윈도우 제한이라 초당 100건 상한이어도 <b>매초 초입에 100건이 겹칠 수 있고</b>,
 * 그러면 해시당 약 19.84MB × 100 ≈ 1.9GB를 한꺼번에 요구한다. 실측에서 힙 1GB에 동시 100건이면
 * 12건이 OOM으로 실패했다. 즉 유입 제어가 있어도 이 경로는 안전하지 않았다.
 *
 * <p><b>대기열을 만들지 않는다.</b> {@link Semaphore#tryAcquire()}로 즉시 거절한다.
 * 큐를 두면 그 큐의 포화 동작을 또 정해야 하고, 기다리는 동안 스레드와 커넥션이 묶인다.
 * 여기서 필요한 건 "지금 못 하면 지금 거절"이다.
 *
 * <p><b>{@code upgradeEncoding}은 통과시킨다.</b> 저장된 해시의 접두사만 보는 문자열 검사라
 * 메모리를 쓰지 않는다. 이걸 막으면 점진 이관만 조용히 멈춘다.
 */
// SecurityConfig(앱 루트)가 조립하므로 public 이다. 모듈 밖 접근은 ModularityTests 가 막는다.
public class HashConcurrencyLimiter implements PasswordEncoder {

    private static final Logger log = LoggerFactory.getLogger(HashConcurrencyLimiter.class);

    private final PasswordEncoder delegate;
    private final Semaphore permits;
    private final Counter rejected;

    public HashConcurrencyLimiter(PasswordEncoder delegate, int maxConcurrent, MeterRegistry meterRegistry) {
        this.delegate = delegate;
        this.permits = new Semaphore(maxConcurrent);
        this.rejected = Counter.builder("auth.hash.rejected")
                .description("동시 실행 상한에 걸려 즉시 거절한 해싱 요청 수. 0보다 크면 상한이 낮거나 로그인이 몰린 것이다")
                .register(meterRegistry);
        log.info("비밀번호 해싱 동시 실행 상한 {}건 (해시당 약 {}MB)",
                maxConcurrent, PasswordHashingCapacityCheck.MEBIBYTES_PER_HASH);
    }

    @Override
    public String encode(CharSequence rawPassword) {
        return within(() -> delegate.encode(rawPassword));
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        return within(() -> delegate.matches(rawPassword, encodedPassword));
    }

    @Override
    public boolean upgradeEncoding(String encodedPassword) {
        return delegate.upgradeEncoding(encodedPassword);
    }

    /** 거절 누계 — 0보다 크면 상한이 낮거나 로그인이 몰리고 있다는 신호다. */
    long rejectedCount() {
        return (long) rejected.count();
    }

    private <T> T within(Supplier<T> body) {
        if (!permits.tryAcquire()) {
            rejected.increment();
            throw new HashCapacityExceededException();
        }
        try {
            return body.get();
        } finally {
            permits.release();
        }
    }
}
