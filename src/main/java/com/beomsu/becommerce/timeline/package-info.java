/**
 * 주문 한 건의 전 과정을 시간순으로 조립하는 <b>읽기 전용</b> 모듈 (ADR-011).
 *
 * <p><b>왜 별도 모듈인가</b>: 조립하려면 여러 모듈을 읽어야 하는데, 그 의존을 어느 도메인
 * 모듈에 얹으면 그 모듈이 오염된다(대사가 정산·원장·에스크로를 알게 되는 식). 그래서 의존을
 * <b>한 방향으로, 한 곳에</b> 가둔다 — {@code timeline → 나머지}이고 역방향은 없다.
 * 다른 모듈들은 서로를 여전히 모른다.
 *
 * <p><b>이 모듈은 의존이 많다.</b> 그건 이 모듈의 일 자체가 "여러 곳을 모으는 것"이라
 * 본질적 복잡도이고, 그 복잡도를 여기 가둔 것이 설계의 요점이다.
 *
 * <p><b>읽기 전용</b>: 상태를 바꾸지 않는다. 조립기가 쓰기를 하면 도메인 규칙을 우회하는
 * 뒷문이 된다.
 *
 * <p><b>AI를 쓰지 않는다</b>: 사실 조립은 결정적이어야 한다. LLM은 금융 표에서 조회는
 * 95.6% 정확하지만 계산은 거의 0%로 붕괴한다(FAITH). 숫자는 코드가 내고, 서술이 필요하면
 * 그건 이 위에 얹는 별개의 층이다.
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {
                "shared",
                "order", "payment", "ledger", "escrow",
                "settlement", "point", "wallet", "dispute",
                "reconciliation", "audit"
        }
)
package com.beomsu.becommerce.timeline;
