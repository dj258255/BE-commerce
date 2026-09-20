package com.beomsu.becommerce;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;

/**
 * 결제 시스템 애플리케이션 진입점.
 *
 * <p>Spring Modulith 애플리케이션으로, {@code com.beomsu.becommerce} 바로 아래의 각 패키지가
 * 하나의 애플리케이션 모듈이 된다(order, payment, ledger, settlement, reconciliation, shared).
 * 모듈 간 통신은 직접 호출이 아니라 도메인 이벤트로 하며, 이벤트 발행은
 * Spring Modulith의 Event Publication Registry(= Transactional Outbox)로 신뢰성을 보장한다.
 */
@Modulithic(systemName = "Pay")
@SpringBootApplication
public class BeCommerceApplication {

    public static void main(String[] args) {
        SpringApplication.run(BeCommerceApplication.class, args);
    }
}
