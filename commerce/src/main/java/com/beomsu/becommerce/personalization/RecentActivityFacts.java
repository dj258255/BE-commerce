package com.beomsu.becommerce.personalization;

import com.beomsu.becommerce.personalization.internal.OnlineContextReader;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 개인화가 <b>다른 모듈에 내주는 활동 사실</b> — 추천이 모델 입력으로 쓰는 최근 상품이다.
 *
 * <p><b>왜 이 클래스가 있나</b>: 컨텍스트를 읽는 코드({@code OnlineContextReader}·{@code ContextStore})는
 * {@code personalization.internal}이라 밖에서 import할 수 없다. 그렇다고 {@code ContextStore}를 열면
 * <b>쓰기 경로까지</b> 열려 남의 컨텍스트를 밖에서 고칠 수 있게 된다. 그래서 이 모듈이
 * <b>무엇을 남에게 보여줄지 스스로 정해</b> 읽기 전용으로만 노출한다 —
 * {@code order.ProductCatalogFacts}·{@code order.OrderTimelineFacts}와 같은 이유, 같은 방식이다(ADR-018).
 *
 * <p>내부 타입({@code OnlineContext})을 그대로 넘기지 않고 <b>상품 id 목록만</b> 내보낸다. 추천이
 * 알아야 하는 것은 "무엇을 봤는가"뿐이고, 순번·적용 시각 같은 내부 사정은 넘길 이유가 없다.
 *
 * <p><b>읽기 실패는 빈 목록이다</b> — 컨텍스트 읽기는 fail-open이므로 저장소가 죽으면 빈 목록이 온다.
 * 그러면 추천은 개인화 없이 동작한다(전체가 죽지 않는다). 폴백한 사실은 저장소 쪽 지표에 남는다.
 */
@Service
public class RecentActivityFacts {

    private final OnlineContextReader contextReader;

    public RecentActivityFacts(OnlineContextReader contextReader) {
        this.contextReader = contextReader;
    }

    /**
     * 이 사용자가 최근에 본 상품 id를 <b>최근 것부터</b> 돌려준다. 활동이 없으면 빈 목록이다.
     *
     * <p>대기하지 않는다({@code waitMs=0}) — 추천 경로에서 "내 이벤트가 반영될 때까지 기다리는" 것은
     * E1의 대기 정책이지 추천의 일이 아니다. 대기를 넣으면 과부하 실험에서 두 정책이 섞인다.
     */
    public List<Long> recentItemIds(long userId, int limit) {
        return contextReader.read(userId, null, 0L).items().stream()
                .map(item -> item.itemId())
                .distinct()
                .limit(Math.max(limit, 1))
                .toList();
    }
}
