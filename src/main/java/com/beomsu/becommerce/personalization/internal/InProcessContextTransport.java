package com.beomsu.becommerce.personalization.internal;

import com.beomsu.becommerce.personalization.UserActivityEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * {@code IN_PROCESS} 전달 — 커밋 뒤 인프로세스 리스너가 컨텍스트를 갱신한다.
 *
 * <p>{@code @ApplicationModuleListener}는 (1) 발행 트랜잭션 커밋 이후에 (2) 비동기로 (3) 자기
 * 트랜잭션에서 실행된다. 즉 <b>근사선 평면의 성질(요청 경로 밖·커밋 이후)은 그대로면서 브로커가
 * 필요 없다.</b> Kafka 없이도 nearline이 성립하는지 보는 것이 E1-b의 절반이다.
 *
 * <p>유실은 없다 — Modulith의 Event Publication Registry(= Outbox)가 같은 트랜잭션에 기록하고,
 * 재기동 시 미완료 발행을 다시 흘린다. 이건 <b>외부화 발행뿐 아니라 중단된 인프로세스 리스너까지</b>
 * 복구한다(이 저장소가 장애로 검증한 경로다).
 *
 * <p>프로퍼티로 게이트한다. 전달 방식 셋 중 하나만 살아 있어야 비교가 성립한다.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.personalization.transport", havingValue = "IN_PROCESS")
class InProcessContextTransport {

    private final ContextApplier applier;
    private final NearlineDelay delay;

    @ApplicationModuleListener
    void on(UserActivityEvent event) {
        delay.apply();
        applier.apply(event);
    }
}
