package com.beomsu.becommerce.shared;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 금액 값 객체 — 최소 단위와 통화.
 *
 * <p>이 테스트가 지키는 것은 <b>통화가 다른 금액을 조용히 더하지 않는다</b>는 것이다.
 * {@code long}만 다루면 1,000원 + $10 이 1,010 이 되고 컴파일도 통과한다.
 */
class MoneyTest {

    private static final Currency USD = Currency.getInstance("USD");
    private static final Currency JPY = Currency.getInstance("JPY");
    private static final Currency KWD = Currency.getInstance("KWD");

    @Test
    @DisplayName("ISO 4217 최소 단위 지수를 통화에서 가져온다")
    void fractionDigitsComeFromIso4217() {
        assertThat(Money.krw(1000).fractionDigits()).isZero();          // 1,000원
        assertThat(Money.of(1000, USD).fractionDigits()).isEqualTo(2);  // $10.00
        assertThat(Money.of(1000, JPY).fractionDigits()).isZero();      // 1,000엔
        assertThat(Money.of(1000, KWD).fractionDigits()).isEqualTo(3);  // 1.000 디나르
    }

    @Test
    @DisplayName("통화가 다르면 더하지 않는다")
    void doesNotAddAcrossCurrencies() {
        assertThatThrownBy(() -> Money.krw(1000).plus(Money.of(1000, USD)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("KRW")
                .hasMessageContaining("USD");
    }

    @Test
    @DisplayName("통화가 다르면 빼지도, 비교하지도 않는다")
    void doesNotSubtractOrCompareAcrossCurrencies() {
        Money krw = Money.krw(1000);
        Money usd = Money.of(1000, USD);

        assertThatThrownBy(() -> krw.minus(usd)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> krw.compareTo(usd)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> krw.isGreaterThan(usd)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("같은 최소 단위라도 통화가 다르면 같은 금액이 아니다")
    void sameNumberDifferentCurrencyIsNotEqual() {
        assertThat(Money.krw(1000)).isNotEqualTo(Money.of(1000, USD));
    }

    @Test
    @DisplayName("같은 통화끼리는 더하고 뺀다")
    void arithmeticWithinSameCurrency() {
        assertThat(Money.krw(1000).plus(Money.krw(500))).isEqualTo(Money.krw(1500));
        assertThat(Money.krw(1000).minus(Money.krw(400))).isEqualTo(Money.krw(600));
    }

    @Test
    @DisplayName("차감 결과가 음수면 예외 — 과다 취소를 값 타입에서 막는다")
    void negativeResultRejected() {
        assertThatThrownBy(() -> Money.krw(1000).minus(Money.krw(1001)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("음수");
    }

    @Test
    @DisplayName("덧셈 오버플로는 조용히 뒤집히지 않고 예외가 난다")
    void additionOverflowThrows() {
        assertThatThrownBy(() -> Money.krw(Long.MAX_VALUE).plus(Money.krw(1)))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    @DisplayName("없는 통화 코드는 만들 수 없다")
    void unknownCurrencyCodeRejected() {
        assertThatThrownBy(() -> Money.of(1000, "XYZ"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
