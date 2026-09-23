package com.beomsu.becommerce.assist.residual;

import com.beomsu.becommerce.assist.draft.DraftPort;
import com.beomsu.becommerce.assist.draft.FactPack;
import java.util.Optional;

/**
 * 잔여 원인 후보 생성기. <b>구현체가 템플릿이든 모델이든 이 뒤에 숨는다.</b>
 *
 * <p>{@link DraftPort}와 같은 이유로 포트를 먼저 둔다. 망분리 규제 때문에 외부 API를
 * 부르는 코드가 도메인에 박히면 안 되고, 모델을 고르기 전에 잣대를 먼저 세워야 한다.
 *
 * <p><b>호출 조건이 계약의 일부다.</b> {@link ResidualCauseService}는 규칙 분류기가
 * 후보를 하나도 못 냈을 때만 이걸 부른다. 산수가 이미 답하는 것을 모델에게 추측시키지
 * 않으려는 것이다.
 */
public interface ResidualCausePort {

    /**
     * 사실 묶음으로 원인 후보 하나를 고른다.
     *
     * @param facts 쓸 수 있는 사실의 전부. <b>여기 없는 숫자를 만들면 안 된다</b>
     * @return 후보. 고를 수 없으면 {@link Optional#empty()} —
     *         <b>기권이 찍는 것보다 낫다.</b> 규칙이 못 가른 건은 애초에 어려운 건이라,
     *         아무거나 고르면 사람의 일이 오히려 는다
     */
    Optional<ResidualSuggestion> suggest(FactPack facts);

    /** 지표와 로그에 찍을 이름. */
    String name();
}
