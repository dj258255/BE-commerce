/**
 * 추천(recommendation) 모듈 — 모델 호출과 <b>과부하 정책</b>.
 *
 * <p><b>이 모듈이 하는 일</b>: 요청이 오면 사용자의 최근 활동을 개인화 모듈에서 받아 <b>모델에 넣고</b>,
 * 모델이 못 대답하면 <b>개인화를 포기하고 인기 상품으로 대답한다.</b> 여기서 재는 것은
 * <b>개인화 coverage와 SLO의 교환비</b>다({@code personalization/docs/02-experiments.md} E3).
 *
 * <p><b>모델은 교체 가능한 dependency다.</b> 이 모듈이 아는 것은 {@link
 * com.beomsu.becommerce.recommendation.internal.ModelClient} 인터페이스뿐이고, 지금 구현은
 * 실험용 스텁이다(용량과 지연을 설정으로 고정한다). 실제 모델 서버를 붙일 때 바뀌는 것은
 * 그 구현 하나다.
 *
 * <p><b>왜 정책이 셋인가</b>: E3의 독립변수다. 모델이 느려질 때 <b>무엇을 포기하는지</b>가 다르다.
 * <ul>
 *   <li>{@code UNBOUNDED} — 아무도 거절하지 않는다. 모두 모델 앞에 줄을 선다. 모델은 살아 있는데
 *       API가 죽는다(스레드가 줄에 갇힌다)</li>
 *   <li>{@code BOUNDED} — 줄의 길이에 상한을 둔다. 넘으면 <b>즉시</b> 포기하고 폴백한다</li>
 *   <li>{@code ADMISSION} — 상한 대신 <b>대기 예상</b>을 본다. 예상이 예산을 넘으면 <b>줄 세우지 않고</b>
 *       포기한다(Little의 법칙). 같은 포기라도 "언제"가 다르다</li>
 * </ul>
 *
 * <p><b>폴백이 싸야 한다.</b> 폴백은 인기 상품 목록을 메모리에서 바로 돌려준다 — DB를 다시 치면
 * 과부하 상황에서 폴백이 두 번째 병목이 된다(그래서 실제 시스템도 이 값을 캐시한다).
 *
 * <p><b>경계</b>: 의존은 {@code shared}(에러 코드) · {@code personalization}(최근 활동) ·
 * {@code order}(제약의 원천인 재고)이다. 각각 루트에 열어 둔 {@code RecentActivityFacts} ·
 * {@code StockAvailabilityFacts} 로만 접근한다(ADR-018). order 는 이 모듈을 의존하지 않으므로
 * 순환이 없고, 어떤 모듈도 이 모듈을 의존하지 않으므로 통째로 떼어낼 수 있다.
 *
 * <p><b>order 의존을 더한 이유(E4 후속)</b>: 제약의 원천은 <b>실제 재고</b>다. 이전에는 합성
 * 가용성(메모리 집합)을 썼는데, 그래서 확인 비용이 0ms 로 재져 "확인을 언제 하는가"의 교환비를
 * 계산할 수 없었다. 그래서 원천을 {@code StockAvailabilityFacts} 로 바꿨다 — 재고를 읽는 것과
 * 재고를 바꾸는 것(실험 계기) 모두 order 가 소유한다. 판단과 대가는 ADR-039.
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = { "shared", "personalization", "order", "experiment" }
)
package com.beomsu.becommerce.recommendation;
