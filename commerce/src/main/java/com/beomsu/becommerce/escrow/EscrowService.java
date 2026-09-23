package com.beomsu.becommerce.escrow;

import com.beomsu.becommerce.payment.recovery.PaymentRecoveryService;
import com.beomsu.becommerce.escrow.internal.EscrowStatus;
import com.beomsu.becommerce.escrow.internal.EscrowHoldView;
import com.beomsu.becommerce.escrow.internal.EscrowHoldRepository;
import com.beomsu.becommerce.escrow.internal.EscrowHold;
import com.beomsu.becommerce.escrow.internal.EscrowException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 에스크로 애플리케이션 서비스 — 보류(HELD) 생명주기의 진입점.
 *
 * <p>결제 승인 이벤트로 {@link #hold}가 보류를 만들고, 구매확정으로 {@link #release}가 정산 가능
 * 상태로 전이하며(정산 파이프라인이 구독할 {@link EscrowReleasedEvent} 발행), 취소로
 * {@link #refundIfHeld}가 환불한다. 무응답분은 {@link #autoReleaseDue}가 배치로 자동 릴리스한다.
 *
 * <p>모든 경로는 <b>멱등</b>하다 — 이벤트가 중복/재전달돼도(Outbox at-least-once) 같은 결과를
 * 보장한다: 중복 hold는 skip, 이미 RELEASED면 재발행 skip, HELD가 아닌 홀드의 환불은 skip.
 */
@Service
@RequiredArgsConstructor
public class EscrowService {

    private static final Logger log = LoggerFactory.getLogger(EscrowService.class);

    private final EscrowHoldRepository repository;
    private final ApplicationEventPublisher events;

    /** 보류 기간(일). 이 기간이 지나도록 구매확정이 없으면 자동 릴리스된다. 기본 7일. */
    @Value("${app.escrow.hold-period-days:7}")
    private long holdPeriodDays;

    /**
     * 결제금 보류 — 결제 승인 시 호출된다. 자동 구매확정 시각은 승인 시각 + 보류 기간.
     *
     * <p>멱등: 같은 주문의 홀드가 이미 있으면 아무 것도 하지 않는다(이벤트 중복 전달 대비).
     */
    @Transactional
    public void hold(String orderNo, long amount, Instant approvedAt) {
        if (repository.findByOrderNo(orderNo).isPresent()) {
            return; // 멱등: 이미 보류함
        }
        Instant autoReleaseAt = approvedAt.plus(Duration.ofDays(holdPeriodDays));
        repository.save(EscrowHold.hold(orderNo, amount, approvedAt, autoReleaseAt));
    }

    /**
     * 구매확정 → 릴리스. 홀드를 RELEASED로 전이하고 {@link EscrowReleasedEvent}를 발행한다.
     *
     * <p>멱등: 이미 RELEASED면 이벤트를 재발행하지 않고 조용히 반환한다(구매확정 재요청·재시도 대비).
     * REFUNDED된 홀드를 릴리스하려 하면 엔티티 가드가 INVALID_ESCROW_STATE로 막는다.
     *
     * @throws EscrowException 홀드가 없으면 ESCROW_NOT_FOUND
     */
    @Transactional
    public void release(String orderNo) {
        EscrowHold hold = repository.findByOrderNo(orderNo)
                .orElseThrow(() -> EscrowException.notFound(orderNo));

        if (hold.getStatus() == EscrowStatus.RELEASED) {
            return; // 멱등: 이미 릴리스됨 — 이벤트 재발행 안 함
        }

        Instant now = Instant.now();
        hold.release(now);
        // 상태 전이(RELEASED)를 saveAndFlush로 명시 영속한다. dirty-check 자동 flush는 readOnly 조회로 세션
        // FlushMode가 MANUAL이거나 detached 엔티티인 경우 신뢰할 수 없어(pay-26 교훈), 이벤트 발행 전에 확정을 강제한다.
        repository.saveAndFlush(hold);
        events.publishEvent(new EscrowReleasedEvent(hold.getOrderNo(), hold.getAmount(), now));
    }

    /**
     * 취소에 따른 환불 — 홀드가 HELD면 REFUNDED로 전이한다(판매자 미정산 확정).
     *
     * <p>멱등: 홀드가 없거나 이미 종결(RELEASED/REFUNDED)됐으면 skip한다. 취소는 구매확정 전
     * (HELD)에만 회수 의미가 있으므로, 이미 릴리스된 홀드는 환불하지 않고 그대로 둔다.
     */
    @Transactional
    public void refundIfHeld(String orderNo) {
        Optional<EscrowHold> found = repository.findByOrderNo(orderNo);
        if (found.isEmpty()) {
            return; // 에스크로에 잡히지 않은 주문(비-에스크로 결제 등) — skip
        }
        EscrowHold hold = found.get();
        if (!hold.isHeld()) {
            return; // 멱등: 이미 릴리스/환불됨 — skip
        }
        hold.refund(Instant.now());
        // 상태 전이(REFUNDED)를 saveAndFlush로 명시 영속한다. dirty-check 자동 flush는 readOnly 조회로
        // 세션 FlushMode가 MANUAL이거나 detached 엔티티인 경우 신뢰할 수 없어(pay-26 교훈) 확정을 강제한다.
        repository.saveAndFlush(hold);
    }

    /**
     * 자동 구매확정 배치 — autoReleaseAt이 지난 HELD 홀드를 릴리스한다. 반환값은 릴리스한 건수.
     *
     * <p>한 건의 실패가 배치 전체를 멈추지 않도록 홀드별 try/catch로 격리한다
     * ({@code PaymentRecoveryService} 패턴). 실패분은 다음 주기에 다시 시도된다.
     */
    public int autoReleaseDue() {
        List<EscrowHold> due =
                repository.findByStatusAndAutoReleaseAtBefore(EscrowStatus.HELD, Instant.now(), chunk());
        int released = 0;
        for (EscrowHold hold : due) {
            try {
                release(hold.getOrderNo());
                released++;
            } catch (Exception e) {
                log.warn("에스크로 자동 릴리스 실패 orderNo={} : {}", hold.getOrderNo(), e.getMessage());
            }
        }
        return released;
    }

    /** 홀드 관측용 — orderNo로 뷰를 조회한다. 없으면 empty. */
    @Transactional(readOnly = true)
    public Optional<EscrowHoldView> getHold(String orderNo) {
        return repository.findByOrderNo(orderNo).map(EscrowHoldView::from);
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
