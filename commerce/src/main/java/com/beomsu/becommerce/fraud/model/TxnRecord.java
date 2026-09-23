package com.beomsu.becommerce.fraud.model;

import java.time.Instant;

/**
 * 피처를 뽑는 데 필요한 한 건의 최소 사실.
 *
 * <p><b>카드 번호도 개인정보도 없다.</b> 여기 들어오는 {@code cardKey} 는 PG 가 준 키이고,
 * 모델은 그 값 자체를 쓰지 않는다. 같은 카드인지 가르는 데만 쓴다.
 *
 * <p><b>왜 이 레코드가 따로 있나</b>: 피처 추출을 순수 함수로 두려는 것이다. 저장소나 Redis 를
 * 안에서 부르면 평가 하네스가 실 인프라 없이는 못 돈다. 지금 규칙 여섯이 보는 창은 Redis 에만
 * 있어서 <b>죽으면 그 창의 집계가 사라진다</b>(docs/17). 그 문제와 이 설계는 별개로 두고,
 * 여기서는 창을 인자로 받는다.
 *
 * @param amount           결제 금액
 * @param at               결제 시각
 * @param deviceId         기기 식별자. 없으면 {@code null}
 * @param ip               IP. 없으면 {@code null}
 * @param installmentMonths 할부 개월. 0 이면 일시불
 */
public record TxnRecord(long amount, Instant at, String deviceId, String ip, int installmentMonths) {

    public TxnRecord(long amount, Instant at, String deviceId, String ip) {
        this(amount, at, deviceId, ip, 0);
    }
}
