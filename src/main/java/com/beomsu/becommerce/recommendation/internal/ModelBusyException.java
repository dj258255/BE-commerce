package com.beomsu.becommerce.recommendation.internal;

/**
 * 모델이 용량을 못 내줘서 포기했다 — <b>모델이 죽은 것과 다르다.</b>
 *
 * <p>죽은 모델은 재시도·서킷브레이커의 문제고, 못 기다린 것은 <b>부하 정책</b>의 문제다.
 * 그래서 예외를 나누고 지표도 나눠 센다. 하나로 뭉치면 "모델을 늘려야 하나, 정책을 바꿔야 하나"를
 * 지표가 답해 주지 못한다.
 */
public class ModelBusyException extends RuntimeException {

    public ModelBusyException(String message) {
        super(message);
    }
}
