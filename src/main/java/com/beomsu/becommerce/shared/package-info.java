/**
 * 공유 커널(shared kernel) 모듈.
 *
 * <p>Money, 식별자(ULID), 공통 예외 등 모든 모듈이 참조하는 값 타입을 담는다.
 * OPEN 타입이라 다른 모듈이 이 패키지의 하위 패키지까지 자유롭게 참조할 수 있다.
 * 공유 커널은 "의존해도 되는 것"만 담아야 하며, 도메인 로직은 넣지 않는다.
 */
@org.springframework.modulith.ApplicationModule(
        type = org.springframework.modulith.ApplicationModule.Type.OPEN
)
package com.beomsu.becommerce.shared;
