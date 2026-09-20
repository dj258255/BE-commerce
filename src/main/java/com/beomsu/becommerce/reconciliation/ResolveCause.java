package com.beomsu.becommerce.reconciliation;

/**
 * 대사 불일치 수기 확정 사유 코드 (ADR-008 Phase 1).
 *
 * <p><b>코드로 받는 이유</b>: 자유 서술만 받으면 같은 원인이 매번 다른 문장으로 남아 집계가 안 된다.
 * 반복되는 원인을 세어 봐야 "이건 규칙으로 자동 확정할 수 있다"를 판단할 수 있다.
 *
 * <p><b>{@link #OTHER}를 둔 이유</b>: 원인 후보는 열려 있고 새 원인이 계속 생긴다. 코드로만 받으면
 * 목록에 없는 원인을 억지로 기존 코드에 밀어 넣게 되어 집계가 오히려 오염된다. OTHER는
 * {@code note}를 필수로 요구해, 나중에 반복되면 정식 코드로 승격한다.
 */
public enum ResolveCause {

    /** 부분취소가 PG 정산 파일에 아직 반영되지 않음 — AMOUNT_MISMATCH의 흔한 원인 */
    PARTIAL_CANCEL_NOT_REFLECTED,

    /** 수수료·부가세 계산 방식 차이 */
    FEE_CALCULATION_DIFF,

    /** 거래일 경계(타임존) — KST 새벽 건이 PG 기준으로 전날/다음날에 잡힘 */
    TIMEZONE_BOUNDARY,

    /** PG 정산 파일이 늦게 도착 — 다음 회차 파일에 포함될 것으로 판단 */
    PG_FILE_DELAY,

    /** 망취소 반영 시점 차이 */
    NET_CANCEL_TIMING,

    /** 내부 기록 유실 — EXTERNAL_ONLY의 위험한 원인. 별도 조치 필요 */
    INTERNAL_RECORD_LOST,

    /**
     * 같은 거래가 정산 파일에 <b>두 번 이상</b> 기록됨.
     *
     * <p>업계에서 반복되는 대사 예외 여섯 유형(타이밍·금액·FX·은행 입금 누락·PSP 기록 누락·
     * <b>중복</b>) 중 하나인데 이 목록에 빠져 있었다. 엔진은 이미 거래 식별자로 중복을 걸러내고
     * 경고를 남기지만, <b>확정하는 사람에게는 고를 어휘가 없었다</b> — OTHER 로 갈 수밖에 없었고
     * 그러면 반복돼도 집계에 안 잡힌다.
     *
     * <p>규칙 제안은 붙이지 않는다. 중복은 매칭 <b>전에</b> 걸러지므로 남은 불일치만 보고는
     * 판단할 수 없다. 대신 실행 요약이 중복 행 수를 보여주고, 사람이 그걸 근거로 고른다.
     */
    DUPLICATE_RECORD,

    /** 위변조 의심 — 확정이 아니라 에스컬레이션 대상 */
    SUSPECTED_TAMPERING,

    /** 위 어디에도 안 맞음. note 필수 */
    OTHER
}
