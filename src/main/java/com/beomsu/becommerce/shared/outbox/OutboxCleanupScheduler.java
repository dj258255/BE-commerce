package com.beomsu.becommerce.shared.outbox;

import com.beomsu.becommerce.SecurityConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.modulith.events.CompletedEventPublications;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Modulith Outbox(event_publication) 완료 이벤트 정리 스케줄러.
 *
 * <p>Modulith의 이벤트 발행 레지스트리(= Transactional Outbox)는 완료된 이벤트를 지우지 않아
 * 무한 성장한다. 이 스케줄러가 {@code retention-days} 이상 지난 <b>완료</b> 발행분을 주기적으로
 * 지운다. {@code app.outbox.cleanup.enabled=true}일 때만 빈으로 등록돼 주기 실행된다(운영).
 * 테스트·기본 부트는 프로퍼티가 꺼져 있어 스케줄링 자체가 비활성이다({@link OutboxCleanupSchedulingConfig}가
 * @EnableScheduling을 같은 게이트로 켠다).
 *
 * <p>루트 패키지({@code com.beomsu.becommerce})에 두어 어떤 Modulith 모듈에도 속하지 않게 한다
 * ({@code SecurityConfig} 등과 동일 레벨) — 모듈 경계 검증에 영향이 없다. Modulith가 자동 구성하는
 * {@link CompletedEventPublications} 빈을 주입받아 정리한다.
 */
@Component
@ConditionalOnProperty(name = "app.outbox.cleanup.enabled", havingValue = "true")
class OutboxCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxCleanupScheduler.class);

    private final CompletedEventPublications completedEventPublications;
    private final long retentionDays;

    OutboxCleanupScheduler(CompletedEventPublications completedEventPublications,
                           @Value("${app.outbox.cleanup.retention-days:7}") long retentionDays) {
        this.completedEventPublications = completedEventPublications;
        this.retentionDays = retentionDays;
    }

    @Scheduled(fixedDelayString = "${app.outbox.cleanup.interval-ms:3600000}")
    public void run() {
        Duration retention = Duration.ofDays(retentionDays);
        // deletePublicationsOlderThan은 void라 삭제 건수를 직접 주지 않는다 → 완료분 총량의
        // before/after 차로 삭제 수를 산출해 로깅한다.
        int before = completedEventPublications.findAll().size();
        completedEventPublications.deletePublicationsOlderThan(retention);
        int deleted = before - completedEventPublications.findAll().size();
        if (deleted > 0) {
            log.info("Outbox 완료 이벤트 정리 completed deleted={} retentionDays={}", deleted, retentionDays);
        }
    }
}
