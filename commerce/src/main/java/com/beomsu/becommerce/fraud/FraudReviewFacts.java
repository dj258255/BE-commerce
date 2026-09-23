package com.beomsu.becommerce.fraud;

import java.time.Instant;
import java.util.List;

/**
 * 심사 한 건을 판단하는 데 필요한 <b>사실만</b> 담는다. 문장은 없다.
 *
 * <p><b>왜 fraud 모듈의 루트에 있나</b>: 심사 초안을 만드는 것은 {@code assist} 인데, 그 모듈이
 * {@code fraud.review} 의 저장소나 엔티티를 직접 보면 경계가 사라진다. 여기 있는 것은 읽기 전용
 * 값이고, 상태를 바꾸는 길은 열지 않는다. 초안 생성이 심사를 닫을 수 있으면 <b>사람이 판정한다</b>는
 * 전제를 우회하는 뒷문이 된다.
 *
 * <p><b>카드 키는 마스킹된 값만 나간다.</b> 원본은 블랙리스트 등록에만 쓰이고 모듈 밖으로 안 간다.
 *
 * @param reviewId          심사 항목 id
 * @param orderNo           주문번호
 * @param paymentId         결제 id
 * @param maskedCardKey     앞4·뒤4만 남긴 카드 키
 * @param amount            결제 금액
 * @param score             규칙 점수 합
 * @param decision          기계 판정 등급 (ALLOW / CHALLENGE / REVIEW / BLOCK)
 * @param detectedAt        탐지 시각
 * @param firedRules        발동한 규칙과 그 규칙의 최근 심사 성적
 * @param sameCardJudged    같은 카드로 심사가 끝난 건수
 * @param sameCardApproved  그중 사람이 <b>정상</b>으로 닫은 건수
 */
public record FraudReviewFacts(
        long reviewId,
        String orderNo,
        long paymentId,
        String maskedCardKey,
        long amount,
        int score,
        String decision,
        Instant detectedAt,
        List<FiredRule> firedRules,
        long sameCardJudged,
        long sameCardApproved) {

    /**
     * 발동한 규칙 하나와 그 규칙의 최근 성적.
     *
     * <p><b>{@code normalRatio} 가 이 묶음의 요점이다.</b> 심사자가 이 건만 보면 "규칙이 걸었으니
     * 의심스럽다"까지만 안다. 그 규칙이 최근 심사에서 열 번 중 아홉 번 정상으로 닫혔다는 것을
     * 같이 보면 판단이 달라진다. 그 수치를 이미 {@code RuleFalsePositiveService} 가 내고 있는데
     * 심사 화면에서는 안 보였다.
     *
     * <p>{@code judged} 가 {@link #MIN_JUDGED_TO_SHOW} 밑이면 {@code normalRatio} 는 {@code null}
     * 이다. <b>2건 중 1건을 50% 로 보여주면 심사자가 그 숫자를 근거로 쓴다.</b>
     *
     * @param name        규칙 이름. 발동 횟수 같은 괄호 안 값은 뗀 것
     * @param detail      괄호 안에 붙어 있던 값. 없으면 {@code null}
     * @param judged      이 규칙이 걸었고 사람이 판정을 끝낸 건수
     * @param normalRatio 그중 정상으로 닫힌 비율. 표본이 얇으면 {@code null}
     */
    public record FiredRule(String name, String detail, long judged, Double normalRatio) {}

    /** 이 밑이면 비율을 안 보여준다. 얇은 표본은 숫자로 주면 근거처럼 읽힌다. */
    public static final long MIN_JUDGED_TO_SHOW = 20;
}
