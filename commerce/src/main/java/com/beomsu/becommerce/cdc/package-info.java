/**
 * CDC 감시(cdc) 모듈(#252).
 *
 * <p>Debezium 커넥터가 떠 있는지(Connect REST)와 실제로 흐르는지(하트비트 토픽)를 지표로 낸다. 다른 모듈에 기대지 않는다.
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {}
)
package com.beomsu.becommerce.cdc;
