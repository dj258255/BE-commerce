package com.beomsu.becommerce.home.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 노출 기록 보존 스케줄러 — 만료된 노출 행을 <b>배치로</b> 지운다(M7 후속, #198③).
 *
 * <p><b>왜 필요한가</b>: {@code home_impressions} 는 <b>요청당 한 행</b>이라 30req/s 로 계속 돌리면
 * 하루 260만 행이다. ADR-043 이 "보존 정책이 없다"를 <b>알려진 한계</b>로 적어 뒀고, 이 스케줄러가
 * 그 한계를 닫는다. 노출은 도메인 상태가 아니라 <b>관측</b>이므로 지워도 잃는 것이 없다
 * (주문·결제처럼 지우면 안 되는 것과 다르다).
 *
 * <p><b>배치로 지운다</b>: 전부를 한 트랜잭션에 지우면 긴 잠금과 큰 롤백 세그먼트가 생겨 홈 조회를
 * 막을 수 있다. 배치 크기만큼 가져와 지우고, 가져온 수가 배치보다 적으면 그 회차를 끝낸다.
 * 한 회차의 배치 반복 횟수에도 상한을 둔다 — 정리 작업이 한 번에 표를 다 비우지 않게 한다.
 *
 * <p><b>보존 일수의 근거는 측정이 아니라 관례다</b>: 지금 이 표를 실제로 쓰는 사람이 없어 "며칠이
 * 맞는가"를 잴 대상이 없다. 아웃박스(7일)와 같은 값을 기본으로 두되, <b>근거가 관례라는 사실을
 * 여기 적어 둔다</b> — 쓰임이 생기면 그때 재서 정한다(ADR-043 「다시 볼 조건」).
 */
@Component
@ConditionalOnProperty(name = "app.home.impression-log.cleanup.enabled", havingValue = "true")
public class ImpressionCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(ImpressionCleanupScheduler.class);

    private final HomeImpressionRepository repository;
    private final long retentionDays;
    private final int batchSize;
    private final int maxBatchesPerRun;

    ImpressionCleanupScheduler(HomeImpressionRepository repository,
                               @Value("${app.home.impression-log.cleanup.retention-days:7}") long retentionDays,
                               @Value("${app.home.impression-log.cleanup.batch-size:5000}") int batchSize,
                               @Value("${app.home.impression-log.cleanup.max-batches-per-run:20}") int maxBatchesPerRun) {
        this.repository = repository;
        this.retentionDays = Math.max(retentionDays, 0);
        this.batchSize = Math.max(batchSize, 1);
        this.maxBatchesPerRun = Math.max(maxBatchesPerRun, 1);
    }

    @Scheduled(fixedDelayString = "${app.home.impression-log.cleanup.interval-ms:3600000}")
    @Transactional
    public void run() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(retentionDays));
        int deleted = 0;
        for (int round = 0; round < maxBatchesPerRun; round++) {
            List<HomeImpression> batch =
                    repository.findByCreatedAtBeforeOrderByIdAsc(cutoff, PageRequest.of(0, batchSize));
            if (batch.isEmpty()) {
                break;
            }
            repository.deleteAllInBatch(batch);
            deleted += batch.size();
            if (batch.size() < batchSize) {
                break;
            }
        }
        if (deleted > 0) {
            log.info("홈 노출 기록 정리 완료 deleted={} retentionDays={} cutoff={}", deleted, retentionDays, cutoff);
        }
    }
}
