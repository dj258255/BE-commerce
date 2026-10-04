package com.beomsu.becommerce.notification;

import com.beomsu.becommerce.notification.consumption.ProcessedEventRepository;
import com.beomsu.becommerce.notification.consumption.ProcessedEvent;
import com.beomsu.becommerce.payment.PaymentConfirmedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 완료 이벤트를 멱등하게 처리한다.
 *
 * <p>Outbox(Modulith 이벤트 레지스트리)는 at-least-once라 같은 이벤트가 중복 전달될 수 있다.
 * (eventKey, consumer) 유니크로 이미 처리한 이벤트는 건너뛴다.
 *
 * <p><b>순서가 멱등의 핵심이다 — 소비 이력을 발송보다 먼저, 같은 트랜잭션에서 적는다.</b>
 * 예전에는 <i>existsBy 확인 → 발송 → 이력 insert</i> 순서라, 중복 2건이 동시에 확인을 통과하면
 * <b>발송이 두 번 나갔다</b>(sender 가 로그 구현이라 잠복해 있을 뿐). 이제 insert 를 먼저 같은 tx 에
 * 적고 강제 flush 하므로, 동시 중복은 유니크 제약에서 직렬화돼 한쪽만 발송에 도달한다.
 *
 * <p><b>insert-먼저가 유실을 만들지 않는 이유</b>: 발송이 실패하면 이 tx 가 롤백되면서 insert 도
 * 함께 사라진다. 그래서 "발송 못 했다"는 사실이 남지 않고, 재전달이 이벤트를 다시 시도한다 —
 * at-least-once 가 유지된다.
 *
 * <p><b>DLQ 격리를 이 경로에서 걷어냈다.</b> 예전에는 발송 실패를 삼키고 DeadLetter 로 격리해
 * 리스너를 정상 종료시켰다. 그런데 insert 를 먼저 하는 순서에서 그렇게 하면 "완료 마킹 + DLQ" 가
 * 같은 tx 에 공존해 <b>모순</b>이 된다 — DLQ 재처리(NotificationAdminService)는 완료 마킹을 보고
 * "이미 처리됨"으로 정리만 하므로 그 알림은 영영 안 나간다. 그래서 발송 실패는 예외로 전파해
 * 롤백·재전달에 맡긴다(포기 로직은 두지 않는다 — 적체는 미완료 발행 나이 지표가 드러낸다).
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

    private static final String CONSUMER = "notification";

    private final ProcessedEventRepository processedEvents;
    private final NotificationSender sender;

    @Transactional
    public void handlePaymentConfirmed(PaymentConfirmedEvent event) {
        String eventKey = "payment-confirmed-" + event.paymentId();

        // 이미 처리한 이벤트면 아무 것도 하지 않는다(재전달의 일반 경로).
        if (processedEvents.existsByEventKeyAndConsumer(eventKey, CONSUMER)) {
            return;
        }

        // 이력을 <발송보다 먼저> 같은 tx 에 적고 강제 flush 한다. 동시 중복은 (eventKey, consumer)
        // 유니크가 직렬화해 한쪽만 발송에 도달한다.
        //
        // 진 쪽의 DataIntegrityViolationException 은 여기서 삼키지 않고 밖으로 던진다 — 이 저장소 호출은
        // @Transactional 이라 예외를 삼키면 이 tx 가 rollback-only 로 오염돼 커밋이 깨진다(C1 교훈).
        // 던져서 롤백시키면 재전달이 오고, 그때 위 exists 검사가 조용히 끝낸다(발송은 이미 1회).
        processedEvents.saveAndFlush(ProcessedEvent.of(eventKey, CONSUMER));

        // 발송 실패는 예외로 전파 → 롤백 → 위 insert 도 함께 사라짐 → 재전달이 다시 시도한다.
        sender.sendPaymentReceipt(event.orderNo(), event.paymentId(), event.amount());
    }
}
