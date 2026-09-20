package com.beomsu.becommerce.payment.pg;

/**
 * PG 취소 요청.
 *
 * <p><b>{@code cancelSeq}가 이 record의 존재 이유다.</b> 취소는 한 결제에 여러 번 일어나므로
 * "결제 + 금액"으로는 취소 건을 식별할 수 없다. 20,000원 결제에 5,000원 부분취소를 두 번 하면
 * 두 요청이 같은 요청으로 보이고, PG 멱등키가 같아져 두 번째 취소가 <b>전송조차 되지 않은 채</b>
 * 첫 응답이 재생된다. 우리 장부만 10,000원 취소로 남고 고객은 5,000원만 돌려받는다.
 *
 * <p>그래서 결제마다 취소 순번을 매기고 그것을 멱등키에 싣는다. 같은 취소의 재시도는 같은 순번이라
 * 여전히 멱등하고, 서로 다른 취소는 순번이 달라 갈린다.
 *
 * <p>{@code provider}는 이 결제를 승인한 PG다. 이중화를 켜면 승인이 다른 PG로 넘어갈 수 있는데,
 * 취소를 아무 PG에나 보내면 "그런 거래 없음"이 돌아오고 그것이 취소 결과가 된다. 고객 돈은 원 PG에
 * 잡혀 있는데 우리 장부에는 취소로 남는다.
 *
 * @param cancelSeq 이 결제의 몇 번째 취소인가(1부터). 실패한 취소는 순번을 소비하지 않는다.
 * @param provider  이 결제를 승인한 PG 이름. 이중화를 켜기 전에 만들어진 결제는 비어 있다
 */
public record PgCancelCommand(String paymentKey, long cancelAmount, String reason, int cancelSeq,
                              String provider) {

    /** 승인 PG를 모르는 옛 결제용. 라우터가 가용한 첫 PG부터 시도한다 */
    public PgCancelCommand(String paymentKey, long cancelAmount, String reason, int cancelSeq) {
        this(paymentKey, cancelAmount, reason, cancelSeq, null);
    }

    /** PG 멱등키 — 취소 건 단위로 유일하다. */
    public String idempotencyKey() {
        return "cancel:" + paymentKey + ":" + cancelSeq;
    }
}
