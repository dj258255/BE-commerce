package com.beomsu.becommerce.fraud.internal;

/**
 * FDS 평가 입력.
 *
 * <p>velocity 를 세는 키는 <b>카드·기기·IP</b> 셋이다. 카드만 세면 카드를 바꿔 가며 같은
 * 기기·같은 회선에서 두드리는 것을 못 본다. {@code userId} 는 아직 규칙에 쓰지 않는다 —
 * 로그인 없이도 결제가 들어오는 경로가 있어 키로서 빈 값이 많다.
 *
 * <p>{@code installmentMonths} 는 0이면 일시불이다. 할부 자체는 정상이지만, <b>한도 안에서
 * 결제 금액을 키우는 가장 쉬운 수단</b>이라 고액과 겹칠 때 신호가 된다.
 */
public record FraudCheckRequest(long userId, String cardKey, String ip, String deviceId,
                                long amount, int installmentMonths) {

    /** 일시불. */
    public FraudCheckRequest(long userId, String cardKey, String ip, String deviceId, long amount) {
        this(userId, cardKey, ip, deviceId, amount, 0);
    }
}
