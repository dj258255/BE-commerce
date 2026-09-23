package com.beomsu.becommerce.fraud;

import java.util.Optional;

/**
 * 심사 한 건의 사실을 읽는다. <b>fraud 모듈이 밖으로 여는 유일한 조회 창구다.</b>
 *
 * <p>저장소를 열지 않고 이 포트만 여는 이유는 상황 5.1 에서 정한 것과 같다. 저장소를 내주면
 * 조회뿐 아니라 저장까지 열리고, 엔티티를 그대로 내주면 받는 쪽이 상태 전이를 부를 수 있다.
 *
 * <p><b>없는 건은 빈 값이다.</b> 예외로 만들면 부르는 쪽이 "조회 실패"와 "그런 심사가 없음"을
 * 구별하지 못한다. 그 둘을 섞으면 상황 5.1 에서 막아 둔 것과 같은 문제가 생긴다.
 */
public interface FraudReviewFactsPort {

    /** 심사 항목 하나의 사실 묶음. 그런 항목이 없으면 {@link Optional#empty()}. */
    Optional<FraudReviewFacts> factsOf(long reviewId);
}
