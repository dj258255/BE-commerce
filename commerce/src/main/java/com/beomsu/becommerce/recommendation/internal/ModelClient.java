package com.beomsu.becommerce.recommendation.internal;

import java.util.List;

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
}
