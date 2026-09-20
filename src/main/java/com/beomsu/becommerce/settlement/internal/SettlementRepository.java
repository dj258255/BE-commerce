package com.beomsu.becommerce.settlement.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;

interface SettlementRepository extends JpaRepository<Settlement, Long> {

    /** 배치 재실행 멱등성: 그 날짜 정산이 이미 만들어졌는지 확인. */
    boolean existsBySettlementDateAndCurrency(LocalDate settlementDate, String currency);

    /**
     * 판매자별 존재 검사.
     *
     * <p>예전에는 {@code sellerId} 가 {@code null} 인 경우를 따로 다뤘다 — 플랫폼 직판을
     * {@code null} 로 적었는데 {@code = null} 은 아무것도 못 찾아서, 그대로 두면 직판 정산이
     * 매번 새로 만들어져 <b>지급이 두 배가 된다.</b> 플랫폼이 자기 판매자 행을 갖게 된 뒤
     * (V49) 값이 늘 있어서 분기가 사라졌다.
     */
    boolean existsBySettlementDateAndCurrencyAndSellerId(LocalDate settlementDate,
                                                         String currency, long sellerId);
}
