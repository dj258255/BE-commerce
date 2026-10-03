package com.beomsu.becommerce.payment.webhook;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 웹훅 이벤트 저장소. 모듈 내부에서만 사용한다(package-private). */
interface WebhookEventRepository extends JpaRepository<WebhookEvent, Long> {

    /** 멱등 수신 판정용 — 이미 받은 이벤트인지 externalEventId로 조회한다. */
    Optional<WebhookEvent> findByExternalEventId(String externalEventId);

    /** 재시도 시각이 지난 보류 건. 결제 행이 생겼는지 다시 확인할 대상이다. */
    List<WebhookEvent> findByStatusAndNextRetryAtLessThanEqual(WebhookEventStatus status, Instant at, Pageable page);

    /** 관측용 — 상태별 행 수(FAILED 적체 게이지의 소스). */
    long countByStatus(WebhookEventStatus status);

    /** 관측용 — 해당 상태로 마감된 가장 오래된 행의 시각(FAILED 최장 나이 게이지의 소스). 없으면 empty. */
    @Query("select min(e.processedAt) from WebhookEvent e where e.status = :status")
    Optional<Instant> findOldestProcessedAt(@Param("status") WebhookEventStatus status);
}
