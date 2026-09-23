package com.beomsu.becommerce.fraud.review;

/**
 * <b>규칙 하나가 만든 오탐과 정탐</b>. 어느 규칙을 조여야 하는지를 가른다.
 *
 * <p>전체 오탐률({@code fraud.review.false.positive.ratio})은 "규칙이 정상 거래를 잡고 있다"까지만
 * 말한다. 규칙이 여섯이라 <b>그 다음 질문인 "어느 것을"에는 답하지 못한다.</b> 그 답이 없으면
 * 임계를 전부 조이게 되고, 그러면 잘 잡던 규칙의 정탐까지 같이 사라진다.
 *
 * <p><b>발동한 규칙 전부에 센다.</b> 한 건에 규칙 둘이 같이 걸렸는데 사람이 정상으로 닫았다면
 * 양쪽 모두 오탐 쪽으로 올린다. 어느 쪽이 진짜 원인인지는 심사 기록에 안 남아 있고, 한쪽에
 * 몰아주려면 없는 근거를 지어내야 한다. 그래서 규칙별 합계는 심사 건수보다 클 수 있다.
 *
 * @param rule           규칙 이름. 발동 횟수 같은 괄호 안 값은 떼고 이름만 남긴다
 * @param falsePositives 이 규칙이 걸었는데 사람이 <b>정상</b>으로 닫은 건수
 * @param truePositives  이 규칙이 걸었고 사람이 <b>부정</b>으로 확인한 건수
 * @param ratio          {@code falsePositives / judged}. 1.0 이면 이 규칙은 한 번도 안 맞았다
 */
public record RuleFalsePositive(String rule, long falsePositives, long truePositives, double ratio) {

    /** 사람이 판정을 끝낸 건수. 대기 중인 건은 안 들어간다 — 밀릴수록 오탐률이 좋아 보인다. */
    public long judged() {
        return falsePositives + truePositives;
    }

    /**
     * 이 규칙을 손볼 만한가.
     *
     * <p>기준을 전체 오탐률 알림({@code FraudFalsePositiveHigh})과 <b>같은 숫자로 맞췄다.</b>
     * 알림이 울려서 화면을 열었는데 화면의 기준이 다르면, 알림은 울리는데 짚이는 규칙은 없는
     * 상태가 생긴다. 그때 사람은 화면을 안 믿게 된다.
     */
    public boolean actionable() {
        return judged() >= MIN_JUDGED && ratio >= MIN_RATIO;
    }

    /** 이 밑으로는 표본이 얇다. 2건 중 1건이 50% 로 잡히는 것을 막는다. */
    public static final long MIN_JUDGED = 20;

    /** 열 건에 일곱 건이 정상이면 그 규칙은 사람 시간만 쓰고 있다. */
    public static final double MIN_RATIO = 0.7;
}
