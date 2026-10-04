package com.beomsu.becommerce.escrow.internal;

import com.beomsu.becommerce.escrow.EscrowReleasedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 에스크로 릴리스의 <b>트랜잭션 경계</b> — 별도 빈으로 둔 이유는 자기호출을 없애기 위해서다.
 *
 * <p>{@code EscrowService.autoReleaseDue()} 가 같은 빈의 릴리스 메서드를 직접 부르면(self-invocation)
 * 프록시를 타지 않아 {@code @Transactional} 이 무시되고, {@link EscrowReleasedEvent} 가 트랜잭션
 * <b>밖에서</b> 발행돼 {@code @ApplicationModuleListener}(AFTER_COMMIT)가 이벤트를 받지 못한다 —
 * 발행은 미완료로만 남고 정산으로 이어지지 않는다. 그래서 릴리스 본체를 이 빈으로 옮겨, 배치가
 * <b>건별로 프록시를 경유</b>해 각 릴리스가 자기 트랜잭션 안에서 돌게 한다(배치 전체를 한 트랜잭션으로
 * 묶지 않는다 — 한 건 실패가 다른 건을 롤백하지 않게).
 *
 * <p>같은 모듈의 API(루트 {@code EscrowService})가 참조하므로 public 이다.
 */
@Component
@RequiredArgsConstructor
public class EscrowReleaseTx {

    private final EscrowHoldRepository repository;
    private final ApplicationEventPublisher events;

    /**
     * 홀드를 RELEASED 로 전이하고 {@link EscrowReleasedEvent} 를 발행한다. 자신의 트랜잭션 안에서
     * 돌므로 발행이 커밋에 실린다.
     *
     * <p>멱등: 이미 RELEASED 면 이벤트를 재발행하지 않고 조용히 반환한다.
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
}
