package com.beomsu.becommerce.settlement.internal;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;


/**
 * 일 단위 정산 배치 스케줄러.
 *
 * <p>{@code app.settlement.enabled=true}일 때만 빈으로 등록돼 주기 실행된다(운영). 테스트·기본 부트는
 * 프로퍼티가 꺼져 있어 스케줄링 자체가 비활성이다({@link SettlementSchedulingConfig}가 @EnableScheduling을
 * 같은 게이트로 켠다). 처리 로직은 {@link SettlementService#settle(LocalDate)}에 있고, 여기서는 주기만
 * 건다(다른 배치 스케줄러들과 동일한 패턴). 그동안 {@code settle()}은 테스트에서만 호출되던 죽은 배치였다 —
 * 이 스케줄러가 주기 트리거를 붙여 살린다.
 *
 * <p>전일({@code LocalDate.now(SETTLEMENT_ZONE).minusDays(1)}, Asia/Seoul) 정산을 집계한다 — 당일은 아직 거래가 진행 중이라
 * 하루가 지난 뒤 마감한다. {@code settle()}은 그 날짜 정산이 이미 있으면 멱등하게 건너뛴다.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.settlement.enabled", havingValue = "true")
class SettlementScheduler {

    private static final Logger log = LoggerFactory.getLogger(SettlementScheduler.class);

    private final SettlementService settlementService;

    @Scheduled(fixedDelayString = "${app.settlement.interval-ms:86400000}")
    public void run() {
        LocalDate date = LocalDate.now(SettlementService.SETTLEMENT_ZONE).minusDays(1);
        try {
            Settlement settlement = settlementService.settle(date);
            if (settlement != null) {
                log.info("정산 배치 완료 date={} gross={} fee={} feeVat={} net={} items={} payoutDate={}",
                        date, settlement.getGrossAmount(), settlement.getFeeAmount(),
                        settlement.getFeeVatAmount(), settlement.getNetAmount(),
                        settlement.getItemCount(), settlement.getPayoutDate());
            }
        } catch (Exception e) {
            // 한 번의 실행 실패가 스케줄러를 죽이지 않게 삼켜 로깅한다 — 다음 주기에 재시도(멱등).
            log.error("정산 배치 실패 date={}", date, e);
        }
    }
}
