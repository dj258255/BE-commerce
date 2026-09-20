package com.beomsu.becommerce.assist.incident;

import java.util.Optional;

/**
 * 로그 조각을 읽고 <b>원인 유형 하나</b>를 고른다. 규칙이든 모델이든 이 뒤에 숨는다.
 *
 * <p><b>왜 포트를 먼저 두나</b>: 규칙 기준선이 있어야 모델이 <b>규칙보다 나은지</b>를 말할 수 있다.
 * 잔여 후보에서 그걸 안 재고 켰다가 껐다 — 절대 정확도가 아니라 <b>기존 방식 대비</b>가 기준이다.
 */
public interface IncidentAnalysisPort {

    /**
     * @param logs 로그 조각. 여러 줄이다
     * @return 원인 후보. 못 고르겠으면 empty — <b>찍는 것보다 기권이 낫다</b>
     */
    Optional<IncidentDiagnosis> diagnose(String logs);

    /** 지표와 비교표에 찍을 이름. */
    String name();
}
