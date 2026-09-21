package com.beomsu.becommerce.auth;

import com.beomsu.becommerce.auth.HashConcurrencyLimiter;
import com.beomsu.becommerce.auth.internal.HashCapacityExceededException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 유입 제어(RPS)가 있어도 순간 메모리 상한은 보장되지 않는다는 게 이 장치의 이유다.
 * 고정 윈도우는 매초 초입에 상한만큼을 한꺼번에 통과시킬 수 있고, Argon2 메모리는
 * <b>같은 순간에 실행 중인 해시 수</b>에 비례한다.
 */
class HashConcurrencyLimiterTest {

    @Test
    @DisplayName("상한을 넘는 동시 해시는 기다리지 않고 즉시 거절한다")
    void rejectsBeyondLimitWithoutQueueing() throws Exception {
        int limit = 2;
        int callers = 6;
        CountDownLatch inside = new CountDownLatch(limit);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger admitted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        PasswordEncoder blocking = new PasswordEncoder() {
            @Override public String encode(CharSequence raw) {
                admitted.incrementAndGet();
                inside.countDown();
                try {
                    release.await();          // 자리를 잡은 채 붙들어 둔다
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "hashed";
            }
            @Override public boolean matches(CharSequence raw, String encoded) { return true; }
        };

        var limiter = new HashConcurrencyLimiter(blocking, limit, new SimpleMeterRegistry());
        try (ExecutorService pool = Executors.newFixedThreadPool(callers)) {
            for (int i = 0; i < callers; i++) {
                pool.submit(() -> {
                    try {
                        limiter.encode("pw");
                    } catch (HashCapacityExceededException e) {
                        rejected.incrementAndGet();
                    }
                });
            }
            inside.await();                    // 두 자리가 찰 때까지
            Thread.sleep(120);                 // 나머지는 대기가 아니라 거절이어야 한다
            assertThat(admitted.get()).isEqualTo(limit);
            assertThat(rejected.get()).isEqualTo(callers - limit);
            release.countDown();
        }
        assertThat(limiter.rejectedCount()).isEqualTo(callers - limit);
    }

    @Test
    @DisplayName("자리를 반납하면 다음 요청은 통과한다 — 영구 차단이 아니다")
    void permitsAreReturned() {
        var limiter = new HashConcurrencyLimiter(new PasswordEncoder() {
            @Override public String encode(CharSequence raw) { return "h"; }
            @Override public boolean matches(CharSequence raw, String encoded) { return true; }
        }, 1, new SimpleMeterRegistry());

        assertThat(limiter.encode("a")).isEqualTo("h");
        assertThat(limiter.encode("b")).isEqualTo("h");
        assertThat(limiter.rejectedCount()).isZero();
    }

    @Test
    @DisplayName("접두사만 보는 upgradeEncoding은 자리를 쓰지 않는다 — 막으면 점진 이관이 멈춘다")
    void upgradeEncodingIsNotLimited() {
        var limiter = new HashConcurrencyLimiter(new PasswordEncoder() {
            @Override public String encode(CharSequence raw) { throw new IllegalStateException(); }
            @Override public boolean matches(CharSequence raw, String encoded) { return true; }
            @Override public boolean upgradeEncoding(String encoded) { return true; }
        }, 0, new SimpleMeterRegistry());

        assertThat(limiter.upgradeEncoding("{bcrypt}$2a$...")).isTrue();
        assertThatThrownBy(() -> limiter.encode("pw"))
                .isInstanceOf(HashCapacityExceededException.class);
    }
}
