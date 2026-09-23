package com.beomsu.becommerce.payment.va;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 가상계좌 상태머신.
 *
 * <p>토스페이먼츠 가상계좌 흐름({@code WAITING_FOR_DEPOSIT → DONE / CANCELED / EXPIRED})을 기반으로
 * 하되, {@link com.beomsu.becommerce.payment.PaymentStatus}와 동일하게 허용 전이표({@link #TRANSITIONS})만
 * 선언하고 불법 전이는 {@link #canTransitionTo}가 막는다.
 *
 * <p>특이 전이: <b>DONE → WAITING_FOR_DEPOSIT 역전이</b>. 일부 은행(신한 등)은 입금 실패인데
 * DONE 통보가 먼저 오고 최대 2분 뒤 되돌리는 통보("지연 통보")를 보낸다. 이 역전이를 허용 전이로
 * 등록해 이미 발송한 "결제 완료" 후속 처리를 보상할 수 있게 한다.
 */
public enum VaStatus {

    /** 가상계좌 발급 완료 — 입금 대기 */
    WAITING_FOR_DEPOSIT,
    /** 입금 완료 (최종은 아님 — 은행 지연 통보로 역전이·취소 가능) */
    DONE,
    /** 입금기한(dueDate) 경과 (terminal) */
    EXPIRED,
    /** 취소 (terminal) */
    CANCELED;

    private static final Map<VaStatus, Set<VaStatus>> TRANSITIONS = Map.of(
            WAITING_FOR_DEPOSIT, EnumSet.of(DONE, EXPIRED, CANCELED),
            // 일부 은행 DONE→WAITING 역전이(입금 실패인데 DONE이 먼저 온 뒤 되돌리는 지연 통보)
            DONE,                EnumSet.of(WAITING_FOR_DEPOSIT, CANCELED),
            EXPIRED,             Collections.emptySet(),
            CANCELED,            Collections.emptySet()
    );

    public boolean canTransitionTo(VaStatus target) {
        return TRANSITIONS.getOrDefault(this, Collections.emptySet()).contains(target);
    }

    public boolean isTerminal() {
        return TRANSITIONS.getOrDefault(this, Collections.emptySet()).isEmpty();
    }
}
