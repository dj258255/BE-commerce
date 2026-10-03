package com.beomsu.becommerce.order.compensation;

/**
 * 보상 태스크의 <b>마지막 시도 결과</b> 분류.
 *
 * <p>상태 머신({@link CompensationStatus})과 별개다. 상태는 "앞으로 다시 시도하나"만 말하고
 * ({@code PENDING}/{@code DONE}/{@code FAILED}), 실제로 <b>무엇이 일어났는지</b>는 말하지 않는다.
 * 특히 실패가 "확정 실패"인지 "결과를 모름"인지 구분되지 않으면, 타임아웃으로 응답을 못 받은
 * 건과 PG 가 거절한 건이 같은 것으로 보인다 — 전자는 취소가 실제로 나갔을 수 있어 재시도가
 * 정답이고 후자는 재시도가 무의미하다.
 */
public enum CompensationOutcome {
    /** 취소 성공, 또는 이미 취소 확정/취소할 원거래 없음이 확정돼 완료로 닫힘. */
    SUCCEEDED,
    /** 재시도해도 결과가 같은 확정 실패(연결 거부, PG 거절, 취소 대상 없음 등). */
    FAILED_DEFINITE,
    /** 요청이 나간 뒤 응답을 받지 못해 결과를 모른다(읽기 타임아웃 등). 미확정을 모른 채 단정하지 않는다. */
    OUTCOME_UNKNOWN,
    /** 결제가 아직 미확정(UNKNOWN·IN_PROGRESS)이라 이번 주기에는 보류했다. 실패가 아니므로 예산을 태우지 않는다. */
    SKIPPED_UNRESOLVED
}
