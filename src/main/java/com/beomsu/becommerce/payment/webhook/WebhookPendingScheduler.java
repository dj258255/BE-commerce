package com.beomsu.becommerce.payment.webhook;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * 승인 응답보다 <b>먼저 도착한</b> 웹훅을 다시 처리한다.
 *
 * <p><b>왜 아웃박스 재시도로 안 되나</b>: {@code WebhookService#process} 가 예외를 던지면 Modulith 가
 * 발행을 미완료로 남기지만, 그 재발행은 {@code republish-outstanding-events-on-restart} 라
 * <b>앱을 재기동해야</b> 돈다. 그 사이 이 웹훅은 처리되지 않는다. PG 쪽 재전송도 기대할 수 없다.
 * 수신 시점에 이미 200 을 돌려줬기 때문이다.
 *
 * <p><b>그래서 이 문제만의 재시도 고리를 둔다.</b> 상태 이름이 {@code PENDING_PAYMENT} 인 것도
 * "실패해서 다시"가 아니라 "결제 행을 기다리는 중"이라는 뜻을 코드에 남기려는 것이다.
 *
 * <p><b>상한을 둔다.</b> 승인이 끝내 실패해 결제 행이 영영 안 생길 수도 있다. 그때까지 계속 돌면
 * 죽은 웹훅이 매 주기 조회를 부른다. 상한을 넘기면 FAILED 로 넘겨 사람이 보게 한다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.webhook.pending-retry.enabled", havingValue = "true")
@RequiredArgsConstructor
public class WebhookPendingScheduler {

    private final WebhookEventRepository repository;
    private final WebhookService webhookService;

    /** 이 횟수를 넘기면 FAILED. 5초 간격이므로 기본값은 약 1분을 기다린다는 뜻이다. */
    @Value("${app.webhook.pending-max-retry:12}")
    private int maxRetry;

    @Scheduled(fixedDelayString = "${app.webhook.pending-interval-ms:5000}")
    public void retryPending() {
        List<WebhookEvent> pending =
                repository.findByStatusAndNextRetryAtLessThanEqual(
                        WebhookEventStatus.PENDING_PAYMENT, Instant.now(), chunk());
        for (WebhookEvent event : pending) {
            if (event.getRetryCount() >= maxRetry) {
                event.markFailed("결제 행이 끝내 생기지 않음 — 재시도 " + event.getRetryCount() + "회 소진");
                repository.save(event);
                log.warn("웹훅 보류 재시도 소진 webhookEventId={}", event.getId());
                continue;
            }
            try {
                webhookService.process(event);
            } catch (RuntimeException e) {
                // 한 건 실패가 나머지를 막지 않는다. 다음 주기에 다시 온다.
                log.warn("웹훅 보류 재처리 실패 webhookEventId={} : {}", event.getId(), e.getMessage());
            }
        }
    }

    /**
     * 배치 한 번이 읽는 상한. 남은 것은 다음 주기가 가져간다.
     *
     * <p><b>필드에 기본값을 둔다.</b> {@code @Value} 는 스프링이 만들어 줄 때만 채워지는데,
     * 단위 테스트는 이 서비스를 직접 생성한다. 초기값이 없으면 0 이 되어 페이지 크기가
     * 0 이라고 터진다 — 실제로 그렇게 깨졌다.
     */
    @org.springframework.beans.factory.annotation.Value("${app.batch.read-chunk-size:500}")
    private int readChunkSize = 500;

    /**
     * <b>설정이 0 이나 음수여도 배치를 죽이지 않는다.</b> 잘못된 설정 하나로 돈을 다루는
     * 배치가 멈추는 것보다, 기본값으로 도는 편이 낫다.
     */
    private org.springframework.data.domain.Pageable chunk() {
        return org.springframework.data.domain.PageRequest.of(0, readChunkSize > 0 ? readChunkSize : 500);
    }
}
