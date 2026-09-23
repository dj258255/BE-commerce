package com.beomsu.becommerce.shared;

import java.util.Currency;
import java.util.Objects;

/**
 * 금액 값 객체 — <b>최소 단위 정수 + 통화</b>.
 *
 * <p><b>왜 최소 단위인가</b>: 부동소수점을 쓰지 않는다. 통화마다 최소 단위가 다르므로
 * ISO 4217 이 정한 지수를 그대로 따른다. {@code java.util.Currency#getDefaultFractionDigits()}가
 * 그 값을 준다.
 *
 * <pre>
 *   KRW · JPY   지수 0    1000 = 1,000원
 *   USD · EUR   지수 2    1000 = $10.00
 *   KWD · BHD   지수 3    1000 = 1.000 디나르
 * </pre>
 *
 * <p><b>왜 새 의존을 안 들이는가</b>: JSR-354(Moneta)가 있지만 {@code BigDecimal} 기반이고
 * 라이브러리를 하나 더 들인다. 우리가 필요한 것은 ISO 4217 지수와 통화 불일치 차단 둘뿐이고,
 * 그건 JDK {@code java.util.Currency}로 된다.
 *
 * <p><b>통화가 다르면 연산하지 않는다.</b> 1,000원 + $10 이 조용히 1,010 이 되는 것을
 * 값 타입 수준에서 막는다. 통화를 안 들고 {@code long}만 다루면 이 실수가 컴파일도 통과한다.
 *
 * <p>금액은 음수가 될 수 없다(차·대변의 방향은 별도로 표현한다). 불변이다.
 */
public record Money(long minorUnit, Currency currency) implements Comparable<Money> {

    public static final Currency KRW = Currency.getInstance("KRW");

    public Money {
        Objects.requireNonNull(currency, "통화는 필수입니다");
        if (minorUnit < 0) {
            throw new IllegalArgumentException("금액은 음수일 수 없습니다: " + minorUnit);
        }
    }

    /** 원화 금액. 이 시스템의 기본 통화라 별도 팩토리를 둔다 — 통화를 생략하는 팩토리는 두지 않는다. */
    public static Money krw(long won) {
        return new Money(won, KRW);
    }

    public static Money of(long minorUnit, Currency currency) {
        return new Money(minorUnit, currency);
    }

    /** ISO 4217 세 글자 코드로 만든다. 없는 코드면 {@link IllegalArgumentException}. */
    public static Money of(long minorUnit, String currencyCode) {
        return new Money(minorUnit, Currency.getInstance(currencyCode));
    }

    public static Money zero(Currency currency) {
        return new Money(0, currency);
    }

    /**
     * 이 통화의 최소 단위 지수. KRW는 0, USD는 2, KWD는 3.
     *
     * <p>수수료·부가세처럼 나눗셈이 들어가는 계산에서 어느 자리까지 내릴지를 이 값으로 정한다.
     */
    public int fractionDigits() {
        return currency.getDefaultFractionDigits();
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(this.minorUnit, other.minorUnit), currency);
    }

    /** 차감. 결과가 음수면 예외 — 잔액 부족·과다 취소를 값 타입 수준에서 차단한다. */
    public Money minus(Money other) {
        requireSameCurrency(other);
        if (this.minorUnit < other.minorUnit) {
            throw new IllegalArgumentException(
                    "차감 결과가 음수입니다: %d - %d".formatted(this.minorUnit, other.minorUnit));
        }
        return new Money(this.minorUnit - other.minorUnit, currency);
    }

    public boolean isGreaterThan(Money other) {
        requireSameCurrency(other);
        return this.minorUnit > other.minorUnit;
    }

    public boolean isZero() {
        return this.minorUnit == 0;
    }

    /**
     * 통화가 다르면 비교하지 않는다.
     *
     * <p>환산해서 비교하고 싶어지는 자리인데, 환율은 시점에 따라 달라져 값 타입이 정할 수 없다.
     * 환산이 필요하면 호출부가 적용 환율을 명시해 같은 통화로 만든 뒤 비교해야 한다.
     */
    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return Long.compare(this.minorUnit, other.minorUnit);
    }

    private void requireSameCurrency(Money other) {
        if (!this.currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "통화가 다른 금액은 연산할 수 없습니다: %s vs %s"
                            .formatted(this.currency.getCurrencyCode(), other.currency.getCurrencyCode()));
        }
    }

    @Override
    public String toString() {
        return minorUnit + " " + currency.getCurrencyCode();
    }
}
