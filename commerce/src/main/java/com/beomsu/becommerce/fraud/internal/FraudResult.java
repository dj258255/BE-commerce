package com.beomsu.becommerce.fraud.internal;

import java.util.List;

/** FDS 평가 결과: 위험 점수·판정·근거(발동한 룰). */
public record FraudResult(int score, FdsDecision decision, List<String> reasons) {

    public boolean isBlocked() {
        return decision == FdsDecision.BLOCK;
    }
}
