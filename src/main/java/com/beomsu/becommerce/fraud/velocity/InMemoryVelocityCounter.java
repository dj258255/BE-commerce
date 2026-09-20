package com.beomsu.becommerce.fraud.velocity;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 인메모리 슬라이딩 윈도우 velocity 카운터.
 *
 * <p>키별로 최근 {@value #WINDOW_MS}ms 안의 시도 타임스탬프를 유지하고 그 개수를 센다.
 * 운영에서는 다중 인스턴스 공유를 위해 Redis(Sorted Set)로 교체한다 — 이 인터페이스만 갈아끼우면 된다.
 * 시간 소스는 {@link #now}로 주입 가능해 테스트에서 윈도우 만료를 제어할 수 있다.
 */
@Component
public class InMemoryVelocityCounter implements VelocityCounter {

    private static final long WINDOW_MS = 60_000; // 1분 윈도우

    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();
    private LongSupplier now = System::currentTimeMillis;

    /** 테스트용 — 시간 소스를 주입해 윈도우 만료를 재현한다. */
    void setClock(LongSupplier now) {
        this.now = now;
    }

    @Override
    public synchronized int recordAndCount(String key) {
        long current = now.getAsLong();
        Deque<Long> deque = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        deque.addLast(current);
        // 윈도우를 벗어난 오래된 시도 제거
        while (!deque.isEmpty() && current - deque.peekFirst() > WINDOW_MS) {
            deque.pollFirst();
        }
        return deque.size();
    }
}
