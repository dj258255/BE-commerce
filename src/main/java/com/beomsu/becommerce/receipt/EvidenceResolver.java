package com.beomsu.becommerce.receipt;

/**
 * 증빙 자동 결정 — 카드는 매출전표가 법정 증빙이므로 세금계산서를 중복 발행하지 않는다.
 * 가상계좌·이체는 현금거래라 현금영수증 대상. B2B는 세금계산서.
 */
public final class EvidenceResolver {

    private EvidenceResolver() {
    }

    public static EvidenceType resolve(String method, boolean b2b) {
        if (b2b) {
            return EvidenceType.TAX_INVOICE;
        }
        if (method == null) {
            return EvidenceType.SALES_SLIP;
        }
        return switch (method) {
            case "VIRTUAL_ACCOUNT", "TRANSFER" -> EvidenceType.CASH_RECEIPT; // 현금성
            default -> EvidenceType.SALES_SLIP;                              // CARD 등
        };
    }
}
