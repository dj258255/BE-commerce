package com.beomsu.becommerce.recommendation.internal;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * 제약의 원천 — <b>"지금 이 상품을 팔 수 있는가"</b>에 답하는 하나의 질문.
 *
 * <p>실제 시스템에서 이 답은 <b>커머스 쪽</b>(재고·판매상태)에서 온다. 이 실험은 그 자리를
 * {@link SyntheticAvailability}가 대신한다 — E4 명세가 "재고·판매상태를 <b>합성</b>하고 생성 중
 * 상태를 바꾼다"고 적었기 때문이다. <b>무엇이 합성인지 숨기지 않는다</b>: 상품 id도 합성이고
 * 가용성 신호도 합성이다. 이 실험이 재는 것은 <b>확인 시점의 교환비</b>이고, 그 교환비는 신호가
 * 어디서 오든 같다.
 *
 * <p><b>왜 인터페이스인가</b>: 실제 재고를 읽는 포트로 바꿔 끼우는 자리가 여기다
 * ({@code order}가 {@code ProductCatalogFacts}로 상품 사실을 내주는 것과 같은 방식, ADR-018).
 * 그때 바뀌는 것은 이 구현 하나이고 {@link ConstraintChecker}와 정책은 그대로다.
 *
 * <p><b>{@link #version()}</b>이 지표를 가능하게 한다: "확인 창 안에서 사실이 몇 번 바뀌었는가"를
 * 세려면 바뀐 횟수를 알 수 있어야 한다. 이것이 없으면 위반율이 낮게 나왔을 때
 * <b>"확인이 잘 해서"인지 "바뀐 게 없어서"인지 구분할 수 없다</b>.
 */
public interface AvailabilitySource {

    /**
     * 주어진 id 중 <b>지금 팔 수 없는 것</b>만 골라 돌려준다.
     *
     * <p>모르는 id는 <b>팔 수 없는 것으로 본다</b> — 재고 행이 없다는 뜻이고, 그것은 "없다"이지
     * "있다"가 아니다. 반대로 하면 제약 확인이 조용히 무력해진다(모르면 통과가 아니라 거절이다).
     */
    Set<Long> unavailableAmong(Collection<Long> itemIds);

    /** 지금까지 가용성이 바뀐 횟수(단조 증가). 창 안의 변경량을 재는 데 쓴다. */
    long version();

    /** 확인 대상 id 전체 — 합성 구현이 미리 채워 두는 풀이다. */
    List<Long> knownItemIds();
}
