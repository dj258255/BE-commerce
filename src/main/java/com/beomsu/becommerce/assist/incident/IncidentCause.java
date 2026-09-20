package com.beomsu.becommerce.assist.incident;

/**
 * 장애 원인 유형. <b>내 머리가 아니라 공개 포스트모템 분류에서 가져왔다.</b>
 *
 * <p>목록을 밖에서 가져온 이유: 내가 겪은 장애로 목록을 만들면 <b>내가 겪은 것만</b> 실험 대상이
 * 된다. 잔여 후보에서 손으로 만든 27건이 실제 분포와 달라 결론이 뒤집혔던 것과 같은 함정이다.
 *
 * <p>{@code TIME_SKEW}·{@code CERT_EXPIRY}·{@code CONFIG_DRIFT}·{@code QUEUE_BACKLOG}·
 * {@code REPLICATION_LAG} 다섯은 <b>목록을 가져오지 않았으면 떠올리지 못했을</b> 것들이다.
 */
public enum IncidentCause {

    /** DB 가 느리거나 끊겼다. 타임아웃·커넥션 고갈로 나타난다. */
    DB_TIMEOUT,

    /** 같은 자원을 동시에 고쳐 순서가 꼬였다. 데드락·낙관적 락 충돌. */
    RACE_CONDITION,

    /** 시계가 틀어졌다. <b>웹훅 서명의 timestamp 허용 오차를 넘겨 정상 요청이 거부된다.</b> */
    TIME_SKEW,

    /** 인증서가 만료됐다. 코드를 안 고쳐도 <b>날짜가 되면</b> 터진다. */
    CERT_EXPIRY,

    /** 무배포로 바꾼 임계값·가중치가 틀렸다. 에러가 아니라 <b>동작이 조용히 바뀐다.</b> */
    CONFIG_DRIFT,

    /** 큐가 밀렸다. 에러가 아니라 <b>느려짐과 적체</b>로 나타난다. */
    QUEUE_BACKLOG,

    /** 복제가 늦어 방금 쓴 것을 못 읽는다. 조회가 <b>없다고 답한다.</b> */
    REPLICATION_LAG,

    /** 외부 결제사가 응답하지 않거나 5xx 를 낸다. */
    PG_UNAVAILABLE,

    /** 위 어느 것도 아니다. <b>모르면 모른다고 답한다.</b> */
    UNKNOWN
}
