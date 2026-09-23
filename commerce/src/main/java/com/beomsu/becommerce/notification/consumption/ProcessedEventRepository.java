package com.beomsu.becommerce.notification.consumption;

import org.springframework.data.jpa.repository.JpaRepository;

// 같은 모듈의 다른 패키지가 참조하므로 public 이다. 모듈 밖 접근은 ModularityTests 의
// allowedDependencies 가 막는다.
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, Long> {
    boolean existsByEventKeyAndConsumer(String eventKey, String consumer);
}
