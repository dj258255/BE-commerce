package com.beomsu.becommerce.order.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 멱등키 레코드 정리 스케줄러.
 *
 * <p>{@link IdempotencyRecord}는 유효기간(15일, 토스페이먼츠 정합) TTL을 갖지만, 만료 행을 지우는
 * 장치가 없으면 {@code idempotency_keys} 테이블이 <b>무한 성장</b>한다(아웃박스가 그랬던 것과 같은 계열).
 * 이 스케줄러가 유효기간이 지난 레코드를 주기적으로 벌크 삭제해 15일치로 크기를 유지한다.
 *
 * <p>{@code app.idempotency.cleanup.enabled=true}일 때만 빈으로 등록돼 실행된다(운영). 기본 off라
 * 테스트·부트에 부작용이 없다({@link IdempotencyCleanupSchedulingConfig}가 @EnableScheduling을 같은
 * 게이트로 켠다). {@code @Scheduled} 메서드는 프록시로 호출되므로 {@code @Transactional}이 적용된다
 * (자기호출 우회 아님).
 */
@Component
@ConditionalOnProperty(name = "app.idempotency.cleanup.enabled", havingValue = "true")
class IdempotencyCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyCleanupScheduler.class);

    private final IdempotencyRepository repository;

    IdempotencyCleanupScheduler(IdempotencyRepository repository) {
        this.repository = repository;
    }

    @Scheduled(fixedDelayString = "${app.idempotency.cleanup.interval-ms:3600000}")
    @Transactional
    public void run() {
        int deleted = repository.deleteByExpiresAtBefore(Instant.now());
        if (deleted > 0) {
            log.info("멱등키 정리 완료 deleted={}", deleted);
        }
    }
}
