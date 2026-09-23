package com.beomsu.becommerce.fraud.model;

import com.beomsu.becommerce.dispute.DisputeOutcome;

import java.util.Locale;

/**
 * 그 결제가 실제로 부정이었나. <b>차지백 결과에서 뽑는다.</b>
 *
 * <p><b>운영자 심사와 다른 값이다.</b> 심사를 승인으로 닫았다는 것은 그 사람이 정상이라고
 * 봤다는 뜻이고, 여기 값은 <b>카드 소유자가 이의를 제기하고 발급사가 판정한 결과</b>다.
 * 상황 6.1 이 "운영자 심사 결과이지 따로 검증된 사실은 아니다" 라고 적어 둔 그 빈자리를
 * 메우는 것이 이 라벨이다.
 *
 * <h2>이 라벨도 완전하지 않다</h2>
 * <ul>
 *   <li><b>사유 코드에 잡음이 있다.</b> Visa 10.4(Fraud, Card-Not-Present)와 Mastercard
 *       4837(No Cardholder Authorization)에는 진짜 도용·친구사기·가맹점 오류가 섞여 들어온다.
 *       패소했다고 반드시 도용이었던 것은 아니다</li>
 *   <li><b>안 걸린 부정은 안 보인다.</b> 카드 소유자가 알아채지 못했거나 이의 기한을 놓친 건은
 *       영원히 정상으로 남는다. 그래서 이 라벨로 낸 재현율은 <b>실제보다 높게</b> 나온다</li>
 *   <li><b>늦게 온다.</b> 차지백은 결제 뒤 수십 일에서 몇 달 걸려 도착한다. 오늘 낸 점수를
 *       오늘 채점할 수 없다</li>
 * </ul>
 * 이 셋은 업계가 다 안고 가는 것이고, 없앨 수 있는 것이 아니라 <b>적어 둘 것</b>이다.
 */
public enum FraudLabel {

    /** 부정 확정. 사유가 부정이고 우리가 졌다. */
    FRAUD,

    /** 정상 확정. 우리가 이겼거나, 부정 사유가 아닌 분쟁이었다. */
    LEGITIMATE,

    /** 아직 모른다. 분쟁이 없거나 진행 중이다. */
    UNKNOWN;

    /**
     * 부정 사유로 볼 문자열들.
     *
     * <p>PG 가 실어 주는 {@code reason} 은 자유 문자열이라 표준 코드가 그대로 오기도 하고
     * 설명이 오기도 한다. 그래서 <b>넓게 잡고 포함 여부로 본다.</b> 좁게 잡으면 부정 차지백을
     * 정상으로 세어 재현율이 부풀고, 그건 이 라벨을 쓰는 이유를 없앤다.
     */
    private static final String[] FRAUD_MARKERS = {
            "10.4",        // Visa — Fraud, Card-Absent Environment
            "10.1", "10.2", "10.3", "10.5",   // Visa 사기 계열
            "4837",        // Mastercard — No Cardholder Authorization
            "4840", "4849", "4870", "4871",   // Mastercard 사기 계열
            "FRAUD", "UNAUTHORIZED", "NO_CARDHOLDER_AUTH", "부정", "도용", "미승인",
    };

    /**
     * 분쟁 결과 하나에서 라벨을 뽑는다.
     *
     * <p><b>승패가 안 갈렸으면 {@link #UNKNOWN} 이다.</b> 진행 중인 건을 라벨로 쓰면
     * 분쟁이 밀릴수록 성적이 좋아 보인다. 상황 6.1 에서 오탐률의 분모를 처리된 건으로
     * 묶은 것과 같은 이유다.
     */
    public static FraudLabel of(DisputeOutcome outcome) {
        if (outcome == null || !outcome.settled()) {
            return UNKNOWN;
        }
        if (!isFraudReason(outcome.reason())) {
            // 상품 미도착·중복청구 같은 분쟁이다. 부정거래 판정의 정답으로 쓸 값이 아니다.
            return LEGITIMATE;
        }
        // 부정 사유인데 우리가 이겼다면 발급사가 우리 손을 들어 준 것이라 정상으로 본다.
        return "LOST".equals(outcome.status()) ? FRAUD : LEGITIMATE;
    }

    static boolean isFraudReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return false;
        }
        String upper = reason.toUpperCase(Locale.ROOT);
        for (String marker : FRAUD_MARKERS) {
            if (upper.contains(marker)) {
                return true;
            }
        }
        return false;
    }
}
