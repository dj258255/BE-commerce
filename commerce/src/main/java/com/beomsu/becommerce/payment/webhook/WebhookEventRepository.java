package com.beomsu.becommerce.payment.webhook;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 웹훅 이벤트 저장소. 모듈 내부에서만 사용한다(package-private). */
interface WebhookEventRepository extends JpaRepository<WebhookEvent, Long> {

    /** 멱등 수신 판정용 — 이미 받은 이벤트인지 externalEventId로 조회한다. */
    Optional<WebhookEvent> findByExternalEventId(String externalEventId);

    /** 재시도 시각이 지난 보류 건. 결제 행이 생겼는지 다시 확인할 대상이다. */
    List<WebhookEvent> findByStatusAndNextRetryAtLessThanEqual(WebhookEventStatus status, Instant at, Pageable page);
}
