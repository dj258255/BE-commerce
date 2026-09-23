package com.beomsu.becommerce.dispute;

import java.time.Instant;

/**
 * 분쟁 하나의 결과. <b>이상거래 라벨의 원재료다.</b>
 *
 * <p><b>왜 dispute 가 라벨의 입구인가</b>: 운영자가 심사를 승인으로 닫았다는 것은 그 사람이
 * 정상이라고 봤다는 뜻이지 실제로 정상이었다는 뜻이 아니다. 실제로 도용이었는지는 <b>카드
 * 소유자가 이의를 제기하고 발급사가 판정할 때</b> 정해진다. 그게 차지백이고, 그 입구는
 * 이 모듈에 이미 있다.
 *
 * <p><b>이 값은 잡음을 갖는다.</b> Visa 10.4(Fraud, Card-Not-Present)와 Mastercard 4837
 * (No Cardholder Authorization)에는 <b>진짜 도용·친구사기·가맹점 오류가 섞여 들어온다.</b>
 * 패소했다고 반드시 도용이었던 것은 아니다. 그래서 이 레코드는 사실만 나르고, 무엇을
 * 부정으로 볼지는 받는 쪽이 정한다.
 *
 * @param orderNo      주문번호
 * @param chargebackId 차지백 식별자. 멱등키다
 * @param reason       PG 가 실어 보낸 사유 문자열. 표준 코드가 섞여 온다
 * @param status       {@code OPEN} / {@code EVIDENCE_SUBMITTED} / {@code WON} / {@code LOST}
 * @param resolvedAt   승패가 갈린 시각. 진행 중이면 {@code null}
 */
public record DisputeOutcome(String orderNo, String chargebackId, String reason,
                             String status, Instant resolvedAt) {

    /** 승패가 갈렸는가. 진행 중인 건을 라벨로 쓰면 밀릴수록 라벨이 좋아 보인다. */
    public boolean settled() {
        return "WON".equals(status) || "LOST".equals(status);
    }
}
