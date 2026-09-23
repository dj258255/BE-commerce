package com.beomsu.becommerce.reconciliation.internal;

/**
 * 대사 1회 실행 요약 — 업로드한 PG 정산 파일로 매칭 엔진을 돌린 결과 집계.
 *
 * @param external       파싱된 외부 기록 수(엔진에 투입된 행 수)
 * @param skipped        파싱 중 건너뛴 불량/요약 행 수
 * @param skippedWithAmount <b>금액이 있는데</b> 건너뛴 행 수. 요약행 노이즈와 달리
 *                          <b>돈이 걸린 행</b>이라 사람이 원본을 봐야 한다 —
 *                          수만 세면 요약행 3개와 50만원짜리 3건이 구분되지 않는다
 * @param duplicateRows  거래 식별자가 겹쳐 <b>버린</b> 정산 파일 행 수.
 *                       0이 아니면 PG 파일 생성 자체에 문제가 있다는 신호다
 * @param matched        일치(자동 종결)
 * @param internalOnly   내부에만 있음(PG 누락 의심)
 * @param externalOnly   외부에만 있음(내부 유실 의심)
 * @param amountMismatch 금액 불일치
 * @param pending        사람 확인이 필요한 건(= internalOnly + externalOnly + amountMismatch), 예외 큐로 남는 수
 */
public record ReconRunSummary(
        int external,
        int skipped,
        int skippedWithAmount,
        int duplicateRows,
        int matched,
        int internalOnly,
        int externalOnly,
        int amountMismatch,
        int pending) {
}
