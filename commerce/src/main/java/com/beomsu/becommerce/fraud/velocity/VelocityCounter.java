package com.beomsu.becommerce.fraud.velocity;

/** 단위시간 시도 횟수 카운터(velocity check). 키별로 최근 윈도우 내 시도 수를 센다. */
public interface VelocityCounter {
    /** 이번 시도를 기록하고, 현재 윈도우 내 누적 시도 수를 반환한다. */
    int recordAndCount(String key);
}
