package com.beomsu.becommerce.wallet.internal;

import com.beomsu.becommerce.shared.DomainException;

/** 월렛 도메인 예외. code는 API 에러 응답의 code 필드로 그대로 노출된다. */
public class WalletException extends DomainException {

    /**
     * 선불전자지급수단 <b>기명식 발행한도 200만원</b>.
     *
     * <p>근거: 전자금융거래법상 선불전자지급수단 발행한도(무기명식 50만원, 기명식 200만원)에 대한
     * 금융위원회 유권해석 — 카카오페이머니의 200만원 충전 한도의 법적 근거이다.
     * 충전 후 잔액이 이 값을 넘으면 {@link #limitExceeded}로 거절한다.
     */
    public static final long MAX_BALANCE = 2_000_000L;

    public WalletException(String code, String message) {
        super(code, message);
    }

    /** 전금법 기명 한도 초과 — 충전 후 잔액이 {@link #MAX_BALANCE}를 넘는 경우. */
    public static WalletException limitExceeded(long balance, long amount) {
        return new WalletException("LIMIT_EXCEEDED",
                "충전 한도(기명 %d원)를 초과합니다: 현재 %d, 충전 %d".formatted(MAX_BALANCE, balance, amount));
    }

    /** 잔액 부족 — 차감 요청이 현재 잔액보다 큰 경우. 마이너스 잔액을 차단한다. */
    public static WalletException insufficientBalance(long balance, long amount) {
        return new WalletException("INSUFFICIENT_BALANCE",
                "월렛 잔액이 부족합니다: 잔액 %d, 요청 %d".formatted(balance, amount));
    }
}
