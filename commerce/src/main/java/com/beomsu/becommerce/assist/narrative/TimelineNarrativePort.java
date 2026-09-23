package com.beomsu.becommerce.assist.narrative;

import com.beomsu.becommerce.assist.draft.FactPack;

import java.util.Optional;

/**
 * 운영자가 읽을 <b>한 문단짜리 서술</b>을 만든다. 구현이 템플릿이든 모델이든 이 뒤에 숨는다.
 *
 * <p><b>왜 이 자리인가</b>: 이 프로젝트는 "분류는 규칙, 서술은 모델"로 자리를 갈랐다.
 * 원인 분류는 산수로 결정되는 것이 대부분이라 모델을 붙였다가 <b>규칙 대비 얻는 것이 없어 껐다</b>.
 * 반면 <b>흩어진 사실을 시간순 문장으로 엮는 일은 규칙이 아예 못 한다.</b> 규칙은 문장을 못 쓴다.
 * 그래서 여기는 처음부터 개선폭이 존재하는 자리다.
 *
 * <p><b>고객용 초안과 다른 점</b>: 받는 사람이 운영자다. 내부 용어를 숨길 이유가 없고, 오히려
 * 상태 이름과 코드가 그대로 나와야 사람이 화면과 대조할 수 있다. 고객에게 나가지 않으므로
 * 말투를 다듬는 가드도 필요 없다. <b>남는 위험은 하나뿐이다 — 없는 숫자를 만드는 것.</b>
 *
 * <p><b>상태를 바꾸지 않는다.</b> 읽기 전용이다. 서술이 대사 결과를 건드리면 사람 확인 원칙을
 * 우회하는 뒷문이 된다(ADR-008).
 */
public interface TimelineNarrativePort {

    /**
     * 사실 묶음을 문단 하나로 엮는다.
     *
     * @param facts 쓸 수 있는 사실의 전부. <b>여기 없는 숫자를 만들면 안 된다</b>
     * @return 서술. 못 만들겠으면 {@link Optional#empty()} — 억지로 만든 문장은
     *         읽는 사람의 일을 늘린다
     */
    Optional<String> narrate(FactPack facts);

    /** 지표와 화면에 찍을 이름. 사람이 <b>무엇이 쓴 문장인지</b> 알아야 한다. */
    String name();
}
