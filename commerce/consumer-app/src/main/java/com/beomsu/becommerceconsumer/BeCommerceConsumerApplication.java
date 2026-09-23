package com.beomsu.becommerceconsumer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 결제 이벤트 프로세스 밖 소비자 (정산 알림 데모).
 *
 * <p>메인 pay 앱과 코드·빌드를 전혀 공유하지 않는 별도 프로세스다. 메인 앱이
 * {@code @Externalized}로 Kafka에 내보낸 {@code payment.confirmed}/{@code payment.canceled}
 * 토픽을 구독해, 도메인 코드 무수정으로 다른 프로세스가 결제 이벤트를 받는 것을 실증한다
 * (ADR-005 "프로세스 밖 소비자" 약속의 이행).
 *
 * <p>웹서버 없는 경량 워커로 동작한다({@code spring.main.web-application-type=none}).
 */
@SpringBootApplication
public class BeCommerceConsumerApplication {

    public static void main(String[] args) {
        SpringApplication.run(BeCommerceConsumerApplication.class, args);
    }
}
