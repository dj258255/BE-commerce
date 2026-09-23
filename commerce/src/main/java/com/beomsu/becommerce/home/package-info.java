/**
 * 홈 컴포저(home) 모듈 — <b>페이지를 조립한다.</b> M7.
 *
 * <p><b>이 모듈이 하는 일</b>: 여러 출처에서 후보를 모아 <b>여러 행짜리 홈</b>을 만들고, 페이지 단위
 * 규칙(중복 제거·카테고리 다양성·재고·행 상한)을 적용해 내보낸다. 추천 코어는 <b>행 하나의 재료</b>이고,
 * 홈은 그 재료를 그대로 흘리지 않는다.
 *
 * <p><b>왜 추천 모듈에 안 넣었나</b>: 이슈 #130이 "추천 코어는 별도 경계, 홈 API가 계약으로 소비한다"고
 * 정했다. 페이지 조립은 추천과 <b>다른 관심사</b>다 — 추천은 "무엇이 관련 있는가"를, 홈은 "한 화면을
 * 어떻게 채울 것인가"를 정한다. 후자는 관련도만으로 결정되지 않는다(다양성·재고·행 상한이 서로 충돌한다).
 * 그래서 경계를 나누고, 추천은 {@link com.beomsu.becommerce.recommendation.RecommendationFacts}로만 본다.
 *
 * <p><b>경계</b>: 의존은 {@code recommendation}(추천 코어) · {@code order}(상품 카드·재고) ·
 * {@code personalization}(최근 활동) · {@code shared}다. 각각 루트에 열어 둔 포트로만 접근한다(ADR-018).
 * 어떤 모듈도 이 모듈을 의존하지 않으므로 <b>통째로 떼어낼 수 있다</b> — 홈을 별도 서비스로 뺄 때
 * 바뀌는 것은 배포 단위지 이 모듈의 코드가 아니다.
 *
 * <p><b>정직한 한계</b>: 이 모듈은 <b>품질을 재지 않는다.</b> 중복을 없애고 카테고리를 흩뿌리는 것이
 * 사용자에게 더 나은 화면인지는 <b>여기서 답하지 않는다</b> — 실사용자가 없다(01-architecture 6절).
 * 이 모듈이 만드는 것은 <b>구조</b>이고, 그 구조가 만드는 가치는 온라인 A/B의 몫으로 남긴다.
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = { "shared", "recommendation", "order", "personalization" }
)
package com.beomsu.becommerce.home;
