package com.beomsu.pay.ledger.internal;

/**
 * 계정 하나의 잔액 — 분개 합으로 파생된 값.
 *
 * <p>부호 규약: 자산 계정({@code PG_RECEIVABLE}, {@code CASH})은 차변이 늘리고, 수익 계정
 * ({@code SALES})은 대변이 늘린다. 그래서 {@code SALES} 잔액은 음수로 나오는 것이 정상이다
 * (매출은 대변 방향으로 쌓인다). 부호를 계정별로 뒤집지 않고 있는 그대로 내보내는 이유는,
 * 합의 부호가 곧 분개 방향의 합이라 검증(차대 일치)과 같은 좌표계에 있기 때문이다.
 */
public record LedgerBalance(AccountType account, long balance) {
}
