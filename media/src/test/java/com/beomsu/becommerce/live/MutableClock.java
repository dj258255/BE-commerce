package com.beomsu.becommerce.live;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 테스트 전용 — {@link #set}으로 "지금"을 임의로 옮길 수 있는 시계. 재접속 유예(30초) 같은
 * 시간 의존 로직을 실제로 기다리지 않고 재현한다(R3).
 */
final class MutableClock extends Clock {

    private Instant instant;

    MutableClock(Instant instant) {
        this.instant = instant;
    }

    void set(Instant instant) {
        this.instant = instant;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
