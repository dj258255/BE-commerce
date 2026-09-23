package com.beomsu.becommerce.auth.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Argon2id 메모리 요구량이 이 인스턴스의 힙에 맞는지 기동 직후 한 번 확인한다 (ADR-009).
 *
 * <p><b>왜 필요한가</b>: Argon2id는 해시 <b>1회당</b> {@code memory} 파라미터만큼(현재 19MiB) 힙을
 * 잡는다. BCrypt는 4KB였다. 즉 <b>동시 로그인 수만큼 곱해진다.</b> Tomcat 스레드 상한이 100이므로
 * 최악의 경우 100 × 19MiB ≈ 2GB가 동시에 필요하다.
 *
 * <p>이건 이론이 아니라 실측이다(성능 리포트 11절). 힙 512MB에서:
 * <pre>
 *   동시  25 → 성공 (505MB 사용, 696ms)
 *   동시  50 → <b>50건 전부 OutOfMemoryError</b> (4.2초)
 *   동시 100 → <b>75건 실패</b> (12.1초)
 * </pre>
 * 사용량은 정확히 선형이다 — 동시 100개에서 1,984MB, 즉 <b>동시 1건당 약 20MB</b>.
 *
 * <p><b>실패하는 방식이 특히 나쁘다.</b> 성공이 0.7초일 때 OOM 경로는 12초가 걸린다. 즉 힙이
 * 모자란 상태에서 로그인이 몰리면 <b>느려지면서 죽는다</b> — 응답이 늦으니 클라이언트는 재시도하고,
 * 재시도가 다시 메모리를 잡는다. 알고리즘을 강하게 만든 대가로 로그인 경로에 자초한 DoS가 생긴다.
 * 유입 제어(IP당 5/s, 경로 전체 100/s)가 1차 방어지만, 고정 윈도우라 100건이 같은 순간에 도달할
 * 수 있어 상한 자체가 위험 구간과 겹친다.
 *
 * <p>그래서 <b>기동 시점에 숫자로 알린다.</b> 운영 중 OOM으로 알게 되면 원인이 로그인 경로라는 걸
 * 찾는 데만 한참 걸린다. 부팅을 막지는 않는다 — 스레드가 전부 동시에 해시를 돌리는 건 최악의
 * 경우이고, 실제로는 그보다 낮게 흐른다. 판단은 배포하는 사람이 한다. 다만 <b>모르고 지나가지는
 * 않게</b> 한다.
 */
@Component
// 같은 모듈의 API(루트)가 참조하므로 public 이다. Modulith 문서가 짚듯 internal 에 있어도
// public 이면 컴파일러는 막지 못하며, 모듈 밖 접근은 ModularityTests 의 allowedDependencies 가 막는다.
public class PasswordHashingCapacityCheck {

    private static final Logger log = LoggerFactory.getLogger(PasswordHashingCapacityCheck.class);

    /** SecurityConfig의 Argon2PasswordEncoder memory 파라미터(KiB)와 같아야 한다. */
    static final int ARGON2_MEMORY_KIB = 19456;

    /** 실측 오버헤드 반영값 — 파라미터상 19MiB지만 동시 100건에서 1,984MB가 잡혔다(≈19.84MB). */
    public static final double MEBIBYTES_PER_HASH = 19.84;

    private final int maxThreads;

    PasswordHashingCapacityCheck(@Value("${server.tomcat.threads.max:200}") int maxThreads) {
        this.maxThreads = maxThreads;
    }

    @EventListener(ApplicationReadyEvent.class)
    void check() {
        long maxHeapMib = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        long worstCaseMib = Math.round(maxThreads * MEBIBYTES_PER_HASH);

        if (worstCaseMib > maxHeapMib) {
            log.warn("""
                    비밀번호 해싱 메모리가 힙을 넘을 수 있습니다 \
                    (최악 {}MB = 스레드 {}개 × {}MB, 힙 상한 {}MB). \
                    로그인이 몰리면 OutOfMemoryError로 실패하며, 실패 경로가 성공보다 느립니다. \
                    힙을 늘리거나(-Xmx), server.tomcat.threads.max를 낮추거나, \
                    app.ratelimit.global-per-sec로 로그인 유입을 줄이십시오. ADR-009 참고.""",
                    worstCaseMib, maxThreads, String.format("%.1f", MEBIBYTES_PER_HASH), maxHeapMib);
        } else {
            log.info("비밀번호 해싱 메모리 여유 확인 (최악 {}MB / 힙 {}MB)", worstCaseMib, maxHeapMib);
        }
    }
}
