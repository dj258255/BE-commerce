package com.beomsu.becommerce.recommendation.internal;

import java.util.List;
import java.util.function.LongPredicate;

/**
 * 모델 서빙 dependency — 이 모듈이 아는 유일한 모델 인터페이스.
 *
 * <p>구현이 스텁이든 HTTP 클라이언트든 이 모듈의 나머지는 바뀌지 않는다. 과부하 정책은
 * <b>이 호출이 느려지거나 실패한다</b>는 사실만 알고 동작한다.
 *
 * <p><b>용량이 곧 계약이다</b>: 구현은 동시 처리 수가 정해져 있고, 그 수를 넘으면 호출자가
 * <b>기다린다</b>. {@link ModelBusyException}은 "기다리다 지쳤다"는 뜻이지 "모델이 죽었다"는 뜻이
 * 아니다 — 이 둘은 처방이 다르므로 지표에서도 나눈다.
 */
public interface ModelClient {

    /**
     * 추천 상품 id를 만든다. {@code recentItemIds}는 최근 것부터 정렬돼 있고 빈 목록일 수 있다
     * (활동이 없는 사용자 — 이때는 모델이 인기 상품 쪽으로 답한다).
     *
     * @throws ModelBusyException 모델 용량을 기다리다 시간을 다 썼다
     */
    List<Long> recommend(long userId, List<Long> recentItemIds);

    /**
     * 이 구현이 <b>생성 중에</b> 후보를 걸러낼 수 있는가(E4-b).
     *
     * <p><b>기본은 못 한다({@code false})이다.</b> 그것이 이 계약의 요점이다 — 모델은 교체 가능한
     * dependency이고, 외부 모델 서버가 우리 재고를 알 이유가 없다. 할 수 있는 구현만 재정의한다.
     *
     * <p>호출자는 이 값을 보고 <b>정책을 낮춰야 한다</b>. 못 하는 구현에 제약을 넘기고
     * 넘겼다고 믿는 것이 가장 나쁘다 — 위반이 조용히 나간다.
     */
    default boolean supportsConstrainedGeneration() {
        return false;
    }

    /**
     * 생성 <b>중에</b> {@code allowed}가 거부한 후보를 건너뛰며 만든다. 사후 필터와 달리
     * <b>목록이 짧아지지 않는다</b> — 걸러낸 자리를 다음 후보로 메우기 때문이다.
     *
     * <p>대가는 <b>가용성 조회가 늘어나는 것</b>이다. 사후 필터는 출력(12개)만 읽지만, 이 방식은
     * 건너뛴 후보마다 한 번씩 더 묻는다. 그래서 <b>위반율이 높을수록 비싸진다</b>.
     *
     * @throws UnsupportedOperationException {@link #supportsConstrainedGeneration()}이 false인 구현
     */
    default List<Long> recommend(long userId, List<Long> recentItemIds, LongPredicate allowed) {
        throw new UnsupportedOperationException(
                "이 구현은 생성 중 제약을 못 받는다. supportsConstrainedGeneration()을 먼저 보라");
    }
}
