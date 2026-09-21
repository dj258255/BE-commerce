package com.beomsu.becommerce.recommendation.internal;

/**
 * 제약을 <b>언제</b> 확인하는가 — E4의 독립변수.
 *
 * <p>모델이 만든 목록은 <b>생성 중에 사실이 바뀌면</b> 응답 시점엔 거짓일 수 있다. 재고가 0이 됐는데
 * 그 상품을 추천하는 식이다. 이 enum은 "언제 다시 확인하는가"만 바꾼다 — 확인하는 <b>대상</b>과
 * <b>방법</b>은 하나를 공유한다({@link ConstraintChecker}).
 *
 * <ul>
 *   <li>{@link #NONE} — 확인하지 않는다. 가장 싸고, 위반이 그대로 나간다. <b>기준선</b>이다</li>
 *   <li>{@link #AT_GENERATION_START} — 모델을 부르기 <b>전에</b> 스냅샷을 뜨고, 그 스냅샷으로
 *       걸러낸다. 확인은 한 번뿐이고 모델 호출과 겹치므로 <b>가장 싼 확인</b>이다.
 *       대신 스냅샷이 <b>모델 지연만큼 낡는다</b> — 생성 중에 바뀐 사실을 못 본다</li>
 *   <li>{@link #AFTER_GENERATION} — 모델이 돌아온 <b>뒤에</b> 읽어서 걸러낸다. 생성 구간의 변경을
 *       반영한다</li>
 *   <li>{@link #AT_RESPONSE} — 응답을 내보내기 <b>직전에</b> 읽어서 걸러낸다. 개념상 가장 강하지만
 *       <b>모델 반환과 응답 사이에 다른 일이 없으면 전자와 같다</b> — 그 사실을 숨기지 않으려고
 *       둘을 나눠 두었다(측정이 같게 나오면 같다고 적는다)</li>
 * </ul>
 *
 * <p><b>"강하게"와 "정확하게"는 다르다.</b> 확인을 여러 번 해도 <b>마지막 확인과 응답 사이</b>는
 * 언제나 존재한다. 그 창을 0으로 만드는 방법은 확인을 늘리는 것이 아니라
 * <b>응답을 사실의 결정으로 삼지 않는 것</b>이다 — 그래서 이 실험은 추천 단계와 구매 단계의 보장
 * 수준을 나눠서 결론낸다(E4 판정).
 */
public enum ConstraintPolicy {

    NONE,
    AT_GENERATION_START,
    AFTER_GENERATION,
    AT_RESPONSE;

    /** 모델 호출 전에 스냅샷을 뜨는가. */
    public boolean snapshotsBeforeGeneration() {
        return this == AT_GENERATION_START;
    }

    /** 응답 직전에 다시 읽는가. */
    public boolean rechecksAtResponse() {
        return this == AT_RESPONSE;
    }

    /** 확인을 하는가(= NONE이 아닌가). */
    public boolean checks() {
        return this != NONE;
    }
}
