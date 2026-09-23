package com.beomsu.becommerce.reconciliation.internal;

/**
 * PG 정산 파일의 한 줄 — 외부 기록(불변 입력 DTO).
 *
 * <p>엔티티가 아니라 매칭 엔진의 입력이다. 파일 적재 계층에서 파싱해 넘긴다.
 *
 * @param orderNo      가맹점 주문번호. PG가 계약 필드로 되돌려주는 값이라 매칭의 기준 키다
 *                     (Adyen {@code Merchant Reference}, Stripe {@code payment_metadata},
 *                     PayPal {@code Invoice ID} 등 주요 PG가 모두 제공한다)
 * @param amount       그 행의 금액. 환불·챠지백은 음수로 온다
 * @param transactionId PG가 부여한 <b>행 단위 고유 식별자</b>. 없으면 {@code null}.
 *                     <p>왜 필요한가: 같은 {@code orderNo}가 여러 행으로 오는 데는 두 가지가 있고
 *                     <b>대응이 정반대</b>다.
 *                     <ul>
 *                       <li><b>정상</b> — 승인·환불·챠지백이 각각 별도 행으로 온다.
 *                           원거래와 같은 참조번호를 공유하므로 <b>합산</b>해야 맞다</li>
 *                       <li><b>오류</b> — 같은 거래가 중복 기록됐다. 합산하면 금액이 부풀어
 *                           <b>불일치를 오히려 감춘다</b>(Uber가 실제로 겪은 사례:
 *                           "$100 거래가 두 번 기록되면 $200으로 집계")</li>
 *                     </ul>
 *                     이 둘은 <b>거래 식별자가 있어야만</b> 갈린다. 주요 PG는 전부 제공한다
 *                     (Adyen {@code Psp Reference}, Stripe {@code balance_transaction_id},
 *                     PayPal {@code Transaction ID}).
 */
public record ExternalRecord(String orderNo, long amount, String transactionId) {

    /** 거래 식별자를 주지 않는 PG·레거시 파일용. 그 경우 중복을 가려낼 수 없다. */
    public static ExternalRecord of(String orderNo, long amount) {
        return new ExternalRecord(orderNo, amount, null);
    }
}
