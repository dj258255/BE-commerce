package com.beomsu.becommerce.payment.recovery;

import com.beomsu.becommerce.payment.PaymentStatus;
import com.beomsu.becommerce.payment.internal.PaymentRepository;
import com.beomsu.becommerce.payment.PaymentException;
import com.beomsu.becommerce.payment.PaymentConfirmedEvent;
import com.beomsu.becommerce.payment.internal.Payment;
import com.beomsu.becommerce.payment.pg.PgClient;
import com.beomsu.becommerce.payment.pg.PgQueryResult;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 미확정(UNKNOWN) 결제 복구.
 *
 * <p>승인 API가 타임아웃되면 결제는 UNKNOWN으로 남는다(성공도 실패도 아님). 이 서비스가 주기적으로
 * 그런 결제를 스캔해 <b>PG 조회 API로 실제 상태를 확정</b>한다.
 * <ul>
 *   <li>PG에 승인돼 있으면 → 전진 복구(DONE) + 결제 완료 이벤트 발행</li>
 *   <li>PG에 없으면 → 승인이 실제로 안 된 것 → ABORTED</li>
 *   <li>이미 취소됨 → CANCELED로 반영</li>
 * </ul>
 * {@link PaymentRecoveryScheduler}가 {@code app.recovery.enabled=true}일 때 이 로직을 주기 실행한다(운영).
 * 테스트는 {@link #recoverUnknownPayments()}를 직접 호출한다.
 */
@Service
@RequiredArgsConstructor
public class PaymentRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(PaymentRecoveryService.class);

    /** 이 시간 이상 UNKNOWN으로 머문 결제만 복구 대상 — 진행 중인 정상 요청과 겹치지 않게 한다. */
    private static final Duration MIN_AGE = Duration.ofMinutes(1);

    /** backoff 정책에서 다음 시도를 미루는 상한. 이보다 오래 묻지 않으면 PG 가 확정한 뒤에도 우리가 늦게 안다. */
    static final Duration BACKOFF_CAP = Duration.ofMinutes(10);

    /** backoff 정책의 첫 간격. 이후 두 배씩 늘린다(#248). 기본값은 ADR-057 그대로이고 #330 에서 상한과 함께 다시 쟀다. */
    @org.springframework.beans.factory.annotation.Value("${app.recovery.backoff-base:1m}")
    private Duration backoffBase = Duration.ofMinutes(1);

    @org.springframework.beans.factory.annotation.Value("${app.recovery.backoff-cap:10m}")
    private Duration backoffCap = BACKOFF_CAP;

    private final PaymentRepository paymentRepository;
    private final PgClient pgClient;
    private final ApplicationEventPublisher events;

    /** UNKNOWN 결제를 스캔해 확정한다. 반환값은 처리한 건수. */
    @Transactional
    public int recoverUnknownPayments() {
        Instant now = Instant.now();
        Instant threshold = now.minus(MIN_AGE);
        List<Payment> targets = switch (policy) {
            case "oldest" -> paymentRepository.findByStatusAndRequestedAtBeforeOrderByRequestedAtAsc(
                    PaymentStatus.UNKNOWN, threshold, chunk());
            case "backoff" -> paymentRepository.findRecoverableUnknown(threshold, now, chunk());
            default -> paymentRepository.findByStatusAndRequestedAtBefore(PaymentStatus.UNKNOWN, threshold, chunk());
        };

        int recovered = 0;
        for (Payment payment : targets) {
            try {
                if (resolve(payment)) {
                    recovered++;
                } else {
                    deferred(payment, now);
                }
            } catch (Exception e) {
                // 한 건 실패가 배치 전체를 멈추지 않게 한다. 다음 주기에 다시 시도된다.
                log.warn("결제 복구 실패 paymentId={} : {}", payment.getId(), e.getMessage());
                deferred(payment, now);
            }
        }
        return recovered;
    }

    /**
     * 확정하지 못한 건을 남긴다. {@code backoff} 면 다음 시도를 1·2·4·8분 뒤(상한 {@link #BACKOFF_CAP}, 둘 다 설정으로 바꿀 수 있다)로 민다 —
     * 그래야 앞자리가 비어 뒤의 건이 청크에 들어온다(#248). 다른 정책은 횟수만 남긴다.
     */
    private void deferred(Payment payment, Instant now) {
        try {
            Instant next = null;
            if ("backoff".equals(policy)) {
                next = now.plus(backoffDelay(payment.getRecoveryAttempts()));
            }
            payment.recordRecoveryAttempt(next);
            paymentRepository.saveAndFlush(payment);
        } catch (Exception e) {
            log.warn("복구 시도 기록 실패 paymentId={} : {}", payment.getId(), e.getMessage());
        }
    }

    /**
     * 시도 횟수 {@code attempts} 뒤의 간격: 첫 간격 × 2^attempts, 상한에서 멈춘다.
     * 설정이 0 이나 음수여도 배치를 죽이지 않고 기본값으로 돈다({@link #chunk()} 와 같은 이유).
     */
    Duration backoffDelay(int attempts) {
        Duration base = backoffBase != null && backoffBase.isPositive() ? backoffBase : Duration.ofMinutes(1);
        Duration cap = backoffCap != null && backoffCap.isPositive() ? backoffCap : BACKOFF_CAP;
        Duration delay = base.multipliedBy(1L << Math.min(Math.max(attempts, 0), 20));
        return delay.compareTo(cap) > 0 ? cap : delay;
    }

    /**
     * paymentKey 하나를 PG 조회로 확정한다. 웹훅 수신 시 "페이로드를 믿지 말고 조회로 재검증"하는
     * 경로가 이 메서드를 쓴다. 이미 확정된(DONE/CANCELED 등) 결제면 아무 것도 하지 않는다(멱등).
     */
    /**
     * 결제 행이 있는지만 본다.
     *
     * <p><b>예외를 던지지 않는다</b>: 호출부가 "없음"을 보고 다른 상태를 쓰려는데, 여기서 예외가 나면
     * 그 트랜잭션이 rollback-only 로 오염돼 그 write 마저 버려진다(실 MySQL 통합 테스트로 확인).
     *
     * <p><b>{@code readOnly} 를 안 붙인 것은 습관이 아니다</b>: 붙여도 여기서는 무해하다.
     * 이 메서드는 늘 read-write 트랜잭션 안에서 불리고, <b>참여하는 readOnly 는 물리 트랜잭션에
     * 적용되지 않아</b> FlushMode 가 그대로 AUTO 다({@code ReadOnlyFlushSemanticsTest} ②).
     * FlushMode 가 MANUAL 이 되는 것은 <b>독립된 readOnly 트랜잭션이 새로 열릴 때</b>이고(같은 테스트 ③),
     * 그래서 이 저장소는 상태 전이 지점에서 {@code saveAndFlush} 로 명시 영속한다(pay-26).
     */
    @Transactional(propagation = Propagation.SUPPORTS)
    public boolean exists(String paymentKey) {
        return paymentRepository.findByPaymentKey(paymentKey).isPresent();
    }

    @Transactional
    public void resolveByPaymentKey(String paymentKey) {
        Payment payment = paymentRepository.findByPaymentKey(paymentKey)
                .orElseThrow(() -> new PaymentException("PAYMENT_NOT_FOUND",
                        "결제를 찾을 수 없습니다: " + paymentKey));
        if (payment.getStatus() != PaymentStatus.UNKNOWN
                && payment.getStatus() != PaymentStatus.IN_PROGRESS) {
            return; // 이미 확정됨 — 웹훅이 늦게/중복 도착해도 안전(멱등)
        }
        resolve(payment);
    }

    /** @return 확정했으면 true, PG 가 아직 진행 중이라 미뤘으면 false */
    private boolean resolve(Payment payment) {
        // 승인한 PG에 물어야 한다. 다른 PG는 없는 거래라고 답하고, 그것을 "승인 안 됨"으로
        // 읽으면 살아 있는 결제를 실패로 확정한다
        PgQueryResult pg = pgClient.query(payment.getPaymentKey(), payment.getPgProvider());
        // 상태 전이를 saveAndFlush로 명시 영속한다. dirty-check 자동 flush는 readOnly 조회로 세션
        // FlushMode가 MANUAL이거나 detached 엔티티인 경우 신뢰할 수 없어(pay-26 교훈), 확정 상태를
        // DB에 강제 반영한다(APPROVED는 이벤트 발행 전에).
        switch (pg.status()) {
            case APPROVED -> {
                payment.confirmByRecovery(pg.method());
                paymentRepository.saveAndFlush(payment);
                events.publishEvent(new PaymentConfirmedEvent(
                        payment.getOrderNo(), payment.getId(),
                        payment.getAmount(), payment.getApprovedAt()));
            }
            case NOT_FOUND -> {
                payment.abortByRecovery("복구: PG에 결제 정보 없음(승인 미완료)");
                paymentRepository.saveAndFlush(payment);
            }
            case CANCELED -> {
                payment.markCanceledByPg("복구: PG에서 이미 취소됨");
                paymentRepository.saveAndFlush(payment);
            }
            // PG가 아직 진행 중이라고 답하면 확정하지 않는다. 여기서 실패로 단정하면
            // 승인이 곧 끝날 결제를 우리만 실패로 기록하는 사고가 된다 → 다음 주기에 다시 묻는다
            case IN_PROGRESS -> {
                log.info("복구 보류: PG 진행 중 orderNo={}", payment.getOrderNo());
                return false;
            }
        }
        return true;
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
     * 읽는 순서(#248). {@code unordered}(정렬 없음) · {@code oldest}(오래된 순 명시) · {@code backoff}(확정 못 한 건은 다음
     * 시도를 미루고, 때가 된 것 중 오래된 순). 필드 기본값을 두는 이유는 {@link #readChunkSize} 와 같다.
     */
    @org.springframework.beans.factory.annotation.Value("${app.recovery.policy:backoff}")
    private String policy = "backoff";

    /**
     * <b>설정이 0 이나 음수여도 배치를 죽이지 않는다.</b> 잘못된 설정 하나로 돈을 다루는
     * 배치가 멈추는 것보다, 기본값으로 도는 편이 낫다.
     */
    private org.springframework.data.domain.Pageable chunk() {
        return org.springframework.data.domain.PageRequest.of(0, readChunkSize > 0 ? readChunkSize : 500);
    }
}
