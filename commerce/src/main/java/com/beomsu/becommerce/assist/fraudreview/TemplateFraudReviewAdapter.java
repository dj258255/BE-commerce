package com.beomsu.becommerce.assist.fraudreview;

import com.beomsu.becommerce.fraud.FraudReviewFacts;
import org.springframework.stereotype.Component;

import java.text.NumberFormat;
import java.util.Locale;
import java.util.Optional;

/**
 * 기본 구현 — <b>외부 호출이 없다.</b> 사실을 정해진 틀에 옮겨 담는다.
 *
 * <p>{@code assist.draft.TemplateDraftAdapter} 와 같은 두 가지 일을 한다.
 *
 * <p><b>하나, 대조군.</b> 모델 초안이 "얼마나 나아졌나"를 잴 상대다. 블라인드 비교의 활성화
 * 조건이 "편집률 중앙값이 <b>템플릿보다</b> 낮을 것"이라, 모델 초안과 <b>같은 사실에서 같은
 * 시점에</b> 이 초안도 뽑아야 한다. provider 를 바꿔 두 번 돌리면 두 번째 회차는 심사자가
 * 이미 답을 아는 상태라 표본이 오염된다.
 *
 * <p><b>둘, 폴백.</b> 모델이 죽거나 가드에 걸려 초안이 없을 때 심사자가 빈 화면을 보면 안 된다.
 *
 * <p><b>템플릿은 판단을 쓰지 않는다.</b> "정상으로 보입니다" 같은 문장을 넣으면 그게 곧 판정이
 * 되고, 심사자가 그 문장을 근거로 누른다. 사실만 옮기고 해석은 사람에게 남긴다.
 */
@Component
public class TemplateFraudReviewAdapter implements FraudReviewDraftPort {

    private static final NumberFormat WON = NumberFormat.getIntegerInstance(Locale.KOREA);

    @Override
    public Optional<String> draft(FraudReviewFacts facts) {
        if (facts == null || facts.firedRules().isEmpty()) {
            return Optional.empty();     // 발동한 규칙이 없으면 심사할 거리가 없다
        }

        StringBuilder sb = new StringBuilder();
        sb.append("[심사 ").append(facts.reviewId())
          .append(" · 주문 ").append(facts.orderNo()).append("]\n\n");
        sb.append("결제 ").append(WON.format(facts.amount())).append("원, 카드 ")
          .append(facts.maskedCardKey())
          .append(". 규칙 점수 ").append(facts.score())
          .append("점으로 ").append(facts.decision()).append(" 등급이 됐습니다.\n\n");

        sb.append("발동한 규칙\n");
        for (var rule : facts.firedRules()) {
            sb.append("· ").append(rule.name());
            if (rule.detail() != null) {
                sb.append(" (").append(rule.detail()).append(')');
            }
            if (rule.normalRatio() != null) {
                sb.append(" — 최근 심사 ").append(rule.judged()).append("건 중 ")
                  .append(Math.round(rule.normalRatio() * 100)).append("%가 정상으로 닫혔습니다");
            } else {
                sb.append(" — 판정 표본이 ").append(rule.judged())
                  .append("건이라 비율을 내지 않습니다");
            }
            sb.append('\n');
        }

        sb.append("\n같은 카드 이력\n");
        if (facts.sameCardJudged() == 0) {
            sb.append("· 판정이 끝난 지난 심사가 없습니다\n");
        } else {
            sb.append("· 지난 심사 ").append(facts.sameCardJudged()).append("건 중 ")
              .append(facts.sameCardApproved()).append("건이 정상으로 닫혔습니다\n");
        }

        sb.append("\n---\n")
          .append("이 초안은 시스템 기록을 옮긴 것입니다. ")
          .append("승인·거부는 심사자가 근거를 확인하고 누르십시오.\n");

        return Optional.of(sb.toString());
    }

    @Override
    public String name() {
        return "template";
    }
}
