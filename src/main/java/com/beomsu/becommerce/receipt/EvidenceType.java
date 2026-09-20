package com.beomsu.becommerce.receipt;

/** 법정 증빙 종류. 결제수단이 무엇이냐에 따라 하나로 결정된다(중복 발행 금지). */
public enum EvidenceType {
    SALES_SLIP,   // 매출전표 (카드) — receipt.url로 제공
    CASH_RECEIPT, // 현금영수증 (가상계좌·이체 = 현금성)
    TAX_INVOICE   // 세금계산서 (B2B)
}
