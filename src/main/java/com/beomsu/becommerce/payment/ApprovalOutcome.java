package com.beomsu.becommerce.payment;

/**
 * PG 승인 결과의 <b>모듈 노출용</b> 표현. 체크아웃 사가(ADR-007)에서 orchestrator(order 모듈)가
 * PG 단계(트랜잭션 밖)와 확정 단계(트랜잭션) 사이를 이 값으로 건넨다.
 *
 * <p>내부 PG 어댑터 타입({@code payment.pg.PgApproveResult})은 payment 모듈 내부라 order가 참조하면
 * 모듈 경계를 위반한다. 그래서 payment 루트에 이 노출용 DTO를 두고, {@link PaymentService#pgApprove}가
 * 매핑해 돌려준다 — order는 이 값을 <b>불투명하게</b> 들고 있다가 {@link PaymentService#applyResult}에 넘긴다.
 *
 * <p>{@code provider}는 실제로 승인을 처리한 PG다. 이중화를 켜면 승인이 다른 PG로 넘어갈 수
 * 있는데, 나중에 취소를 아무 PG에나 보내면 "그런 거래 없음"이 돌아오고 그것이 취소 결과가 된다.
 * 고객 돈은 원 PG에 잡혀 있는데 우리 장부에는 취소로 남는다. 그래서 승인 시점에 적어 둔다.
 *
 * @param result     승인 결과 3-상태(SUCCESS/FAILED/TIMEOUT)
 * @param method     승인 수단(성공 시), 그 외 null
 * @param failReason 실패/미확정 사유, 성공 시 null
 * @param provider   승인을 처리한 PG 이름. 단일 PG면 null
 * @param cardFingerprint 같은 카드를 결제 여러 건에 걸쳐 묶는 키. 카드 결제가 아니거나 PG 가
 *                        카드 정보를 안 주면 null. <b>카드 데이터가 아니라 마스킹 값의 해시다</b>
 */
public record ApprovalOutcome(Result result, String method, String failReason, String provider,
                              String cardFingerprint) {

    public ApprovalOutcome(Result result, String method, String failReason) {
        this(result, method, failReason, null, null);
    }

    public ApprovalOutcome(Result result, String method, String failReason, String provider) {
        this(result, method, failReason, provider, null);
    }

    public enum Result { SUCCESS, FAILED, TIMEOUT }
}
