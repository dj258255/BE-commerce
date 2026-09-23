package com.beomsu.becommerce.fraud.review;

/**
 * FDS 심사 항목 조회용 뷰 — 엔티티를 노출하지 않고 심사에 필요한 값만 담는다.
 *
 * <p>{@code cardKey}(PG 결제 키)는 민감하므로 앞4·뒤4만 남기고 마스킹해 노출한다. 실제 블랙리스트
 * 등록은 서비스가 엔티티의 원본 키로 수행하므로, 뷰는 마스킹된 값만 어드민에게 보인다.
 */
public record FraudReviewView(long id, String orderNo, long paymentId, String cardKey, long amount,
                              int score, String decision, String reasons, String status,
                              String reviewedBy) {

    static FraudReviewView from(FraudReview r) {
        return new FraudReviewView(r.getId(), r.getOrderNo(), r.getPaymentId(),
                mask(r.getCardKey()), r.getAmount(), r.getScore(), r.getDecision().name(),
                r.getReasons(), r.getStatus().name(), r.getReviewedBy());
    }

    /** 카드 키 마스킹 — 앞4·뒤4만 남기고 가운데를 가린다. 8자 이하면 전체를 가린다. */
    private static String mask(String cardKey) {
        if (cardKey == null) {
            return null;
        }
        if (cardKey.length() <= 8) {
            return "****";
        }
        return cardKey.substring(0, 4) + "****" + cardKey.substring(cardKey.length() - 4);
    }
}
