package com.beomsu.becommerce.dispute;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 분쟁 결과를 읽는다. <b>dispute 가 밖으로 여는 조회 창구다.</b>
 *
 * <p>저장소를 안 열고 이 포트만 여는 이유는 상황 5.1 에서 정한 것과 같다. 저장소를 내주면
 * 조회뿐 아니라 저장까지 열리고, 엔티티를 그대로 내주면 받는 쪽이 상태 전이를 부를 수 있다.
 * 라벨을 읽는 쪽이 분쟁을 닫을 수 있으면 안 된다.
 */
public interface DisputeOutcomePort {

    /** 그 주문의 분쟁. 없으면 빈 값. */
    Optional<DisputeOutcome> outcomeOf(String orderNo);

    /**
     * 승패가 갈린 분쟁을 최근 것부터. <b>상한을 받는다.</b>
     *
     * <p>상황 2.3 에서 배치 조회 15곳에 상한을 건 것과 같은 이유다. 라벨 채점은 배치라
     * 쌓인 만큼 그대로 읽으면 그만큼 메모리에 올라간다.
     */
    List<DisputeOutcome> settledSince(Instant since, int limit);
}
