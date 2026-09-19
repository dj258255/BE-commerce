package com.beomsu.pay.notification;

import com.beomsu.pay.notification.consumption.ProcessedEventRepository;
import com.beomsu.pay.notification.consumption.ProcessedEvent;
import com.beomsu.pay.notification.consumption.DeadLetterView;
import com.beomsu.pay.notification.consumption.DeadLetterSummary;
import com.beomsu.pay.notification.consumption.DeadLetterRepository;
import com.beomsu.pay.notification.consumption.DeadLetter;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * DLQ 백오피스 어드민 — 죽은 메시지를 조회하고 재처리한다.
 *
 * <p>"결제는 만들고 나서가 진짜"라는 운영 감각의 도구. 알림 발송이 일시 장애로 DLQ에 쌓인 뒤,
 * 채널이 복구되면 운영자가 재처리한다. 재처리 성공 시 DLQ에서 제거하고 처리 완료로 마킹하며,
 * 다시 실패하면 재시도 횟수만 올려 DLQ에 남긴다.
 *
 * <p><b>격리·발견·복구·검증 네 책임 중 여기서 복구와 검증을 맡는다</b>(ADR-030). 격리는
 * {@link com.beomsu.pay.notification.NotificationService} 가 DLQ 적재로, 발견은 남은 건수를
 * 세는 {@link #summary()} 가 한다. 재처리는 <b>멱등</b>하다 — 이미 처리된 이벤트는 다시 보내지
 * 않는다("복구"는 안 간 것을 보내는 것이지 다시 보내는 것이 아니다).
 */
@Service
@RequiredArgsConstructor
public class NotificationAdminService {

    private static final Logger log = LoggerFactory.getLogger(NotificationAdminService.class);
    private static final String CONSUMER = "notification";

    private final DeadLetterRepository deadLetters;
    private final ProcessedEventRepository processedEvents;
    private final NotificationSender sender;

    @Transactional(readOnly = true)
    public Page<DeadLetterView> listDeadLetters(Pageable pageable) {
        return deadLetters.findAll(pageable)
                .map(d -> new DeadLetterView(d.getId(), d.getEventType(), d.getEventKey(),
                        d.getOrderNo(), d.getPaymentId(), d.getAmount(), d.getFailReason(),
                        d.getRetryCount(), d.getCreatedAt()));
    }

    /**
     * 복구 상태 요약 — <b>발견</b>의 표면.
     *
     * <p>격리된 건수와 가장 오래 기다린 시각. 건수만으로는 적체를 못 본다 — 방금 쌓인 열 건과
     * 이틀 묵은 한 건은 위험이 다르다. "발견과 복구 사이가 사람의 속도로 벌어지는" 것을 이 숫자가
     * 드러낸다.
     */
    @Transactional(readOnly = true)
    public DeadLetterSummary summary() {
        return new DeadLetterSummary(deadLetters.count(), deadLetters.findOldestCreatedAt().orElse(null));
    }

    /**
     * DLQ 항목을 재처리한다. 성공하면 제거+완료 마킹, 실패하면 재시도 횟수만 올린다.
     *
     * <p><b>멱등.</b> 이미 처리 완료로 마킹된 이벤트는 재발송하지 않고 정리만 한다. 이 검사가
     * 없으면 이런 순서에서 <b>알림이 두 번 나간다</b>: 1차 배달이 DLQ로 격리 → 재배달이 성공해
     * 완료 마킹 → 남아 있던 DLQ 항목을 운영자가 재처리 → 같은 알림을 또 보낸다.
     */
    @Transactional
    public boolean reprocess(Long deadLetterId) {
        DeadLetter dl = deadLetters.findById(deadLetterId)
                .orElseThrow(() -> new IllegalArgumentException("DLQ 항목 없음: " + deadLetterId));

        if (processedEvents.existsByEventKeyAndConsumer(dl.getEventKey(), CONSUMER)) {
            deadLetters.delete(dl);
            log.info("DLQ 재처리: 이미 처리된 이벤트 → 재발송 없이 정리 eventKey={}", dl.getEventKey());
            return true;
        }

        try {
            sender.sendPaymentReceipt(dl.getOrderNo(), dl.getPaymentId(), dl.getAmount());
            processedEvents.save(ProcessedEvent.of(dl.getEventKey(), CONSUMER));
            deadLetters.delete(dl);
            return true;
        } catch (RuntimeException ex) {
            dl.incrementRetry();   // 여전히 실패 — DLQ에 남기고 재시도 횟수만 증가
            return false;
        }
    }
}
