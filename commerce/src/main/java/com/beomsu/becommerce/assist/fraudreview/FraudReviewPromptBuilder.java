package com.beomsu.becommerce.assist.fraudreview;

import com.beomsu.becommerce.fraud.FraudReviewFacts;
import org.springframework.stereotype.Component;

import java.text.NumberFormat;
import java.util.Locale;

/**
 * 프롬프트를 만든다. <b>사실은 여기서 다 넣고, 모델에게는 문장만 시킨다.</b>
 *
 * <p><b>판정을 시키지 않는 것이 이 프롬프트의 요점이다.</b> "이 건이 부정거래인지 판단하라"고
 * 시키면 모델은 반드시 답을 낸다. 근거가 없어도 낸다. 상황 3.3 에서 확인한 그대로다 —
 * 근거 없이 단정한 답이 출력 검증 다섯을 전부 통과했고, <b>맞은 답보다 틀린 답의 신뢰도가
 * 높았다.</b> 그래서 여기서는 심사자가 무엇을 봐야 하는지를 정리하는 데까지만 시킨다.
 *
 * <p><b>숫자는 준 것만 쓰게 한다.</b> 그래도 지어내면
 * {@code assist.draft.NumericProvenanceGuard} 가 초안을 버린다. 프롬프트는 1차 방어이고
 * 가드가 2차다. 프롬프트만으로 막힌다고 보면 안 된다.
 */
@Component
public class FraudReviewPromptBuilder {

    private static final NumberFormat WON = NumberFormat.getIntegerInstance(Locale.KOREA);

    String system() {
        return """
               너는 결제 이상거래 심사자를 돕는 보조다. 심사자가 승인·거부를 누르기 전에
               읽을 짧은 정리를 쓴다.

               지켜야 할 것
               1. 아래 <사실> 에 있는 숫자만 쓴다. 없는 금액·비율·건수를 만들지 마라.
               2. 부정거래인지 아닌지 판단하지 마라. "정상으로 보인다", "차단해야 한다" 같은
                  결론을 쓰면 안 된다. 무엇을 확인해야 하는지까지만 쓴다.
               3. 사실에 없는 정보를 추측해 채우지 마라. 모르는 것은 모른다고 쓴다.
               4. 다섯 문장 이내. 표나 목록 없이 줄글로 쓴다.
               5. 한국어로 쓴다.
               6. 금액은 사실에 적힌 자릿수 그대로 쓴다(980,000원). 만·억 단위로 줄이지 마라.
                  줄여 쓰면 심사자가 원래 값을 다시 찾아야 하고, 금액 결손 검사에도 걸린다.
               """;
    }

    String user(FraudReviewFacts f) {
        StringBuilder sb = new StringBuilder();
        sb.append("<사실>\n");
        sb.append("주문번호: ").append(f.orderNo()).append('\n');
        sb.append("결제 금액: ").append(WON.format(f.amount())).append("원\n");
        sb.append("카드: ").append(f.maskedCardKey()).append('\n');
        sb.append("규칙 점수: ").append(f.score()).append("점, 등급: ").append(f.decision()).append('\n');

        sb.append("발동한 규칙:\n");
        for (var r : f.firedRules()) {
            sb.append("  - ").append(r.name());
            if (r.detail() != null) {
                sb.append(" (").append(explain(r.name(), r.detail())).append(')');
            }
            if (r.normalRatio() != null) {
                sb.append(": 최근 판정 ").append(r.judged()).append("건 중 ")
                  .append(Math.round(r.normalRatio() * 100)).append("%가 정상으로 닫힘");
            } else {
                // 얇은 표본을 비율로 주면 모델이 그것을 근거처럼 쓴다. 건수만 준다.
                sb.append(": 판정 표본 ").append(r.judged()).append("건. 비율을 낼 만큼 쌓이지 않음");
            }
            sb.append('\n');
        }

        sb.append("같은 카드의 지난 심사: ");
        if (f.sameCardJudged() == 0) {
            sb.append("판정이 끝난 건이 없음\n");
        } else {
            sb.append(f.sameCardJudged()).append("건 중 ")
              .append(f.sameCardApproved()).append("건이 정상으로 닫힘\n");
        }
        sb.append("</사실>\n\n");
        sb.append("위 사실로 심사자가 무엇을 확인해야 하는지 정리해라.");
        return sb.toString();
    }

    /**
     * 되묻기. <b>지적만 주고 새로 쓰라고는 안 한다.</b>
     *
     * <p>새로 쓰게 하면 멀쩡하던 문장이 흔들려 무엇이 나아졌는지 못 가른다. 원본을 주고
     * 지적한 자리만 고치게 한다. {@code assist.draft} 의 되묻기와 같은 모양이다.
     */
    String revise(FraudReviewFacts f, String original, java.util.List<String> issues) {
        StringBuilder sb = new StringBuilder();
        sb.append("아래 초안에서 지적한 자리만 고쳐 다시 써라. 지적이 없는 문장은 그대로 둔다.\n\n");
        sb.append("[지적]\n");
        for (String i : issues) {
            sb.append("- ").append(i).append('\n');
        }
        sb.append("\n[초안]\n").append(original).append("\n\n[사실]\n").append(user(f));
        return sb.toString();
    }

    /**
     * 규칙 값에 <b>이름을 붙여</b> 준다.
     *
     * <p><b>왜 필요한지는 재고 알았다.</b> 값을 {@code 980000/1000000} 꼴로 그대로 주니
     * 모델이 <b>앞 숫자를 임계값으로</b> 읽어 "임계값 980,000원" 이라고 썼다. 세 건에서 그랬다.
     * {@code 3/1m} 은 <b>"1만 건 기준"</b> 으로 읽었다 — {@code 1m} 을 백만으로 본 것이다.
     *
     * <p><b>템플릿은 안 바꾼다.</b> 템플릿은 이 실험의 기준선이고 사람은 이 표기를 오해하지
     * 않았다. 둘 다 바꾸면 이번에 잰 값과 다음에 잴 값을 견줄 수 없다.
     *
     * <p>모르는 규칙은 값을 그대로 준다. 억지로 풀면 없는 뜻을 붙이게 된다.
     */
    static String explain(String rule, String detail) {
        if (rule == null || detail == null) {
            return "값 " + detail;
        }
        return switch (rule) {
            case "NEAR_THRESHOLD" -> nearThreshold(detail);
            case "VELOCITY_EXCEEDED" -> velocity(detail);
            case "MICRO_PROBE" -> "소액 결제 " + won(detail) + "원";
            case "HIGH_AMOUNT" -> "결제 금액 " + won(detail) + "원";
            case "DEVICE_CHURN" -> "쓰인 기기 " + detail + "개";
            case "IP_CHURN" -> "쓰인 IP " + detail + "개";
            default -> "값 " + detail;
        };
    }

    /** {@code 980000/1000000} 을 <b>결제 금액과 임계로 갈라</b> 준다. */
    private static String nearThreshold(String detail) {
        String[] p = detail.split("/");
        if (p.length != 2) {
            return "값 " + detail;
        }
        return "결제 금액 " + won(p[0]) + "원, 고액 임계 " + won(p[1]) + "원";
    }

    /** {@code 3/1m} 의 뒤쪽은 <b>백만이 아니라 1분</b>이다. */
    private static String velocity(String detail) {
        String[] p = detail.split("/");
        if (p.length != 2) {
            return "값 " + detail;
        }
        String window = switch (p[1]) {
            case "1m" -> "1분";
            case "5m" -> "5분";
            case "1h" -> "1시간";
            default -> p[1];
        };
        return "최근 " + window + " 안에 " + p[0] + "건";
    }

    private static String won(String raw) {
        try {
            return WON.format(Long.parseLong(raw.trim()));
        } catch (NumberFormatException e) {
            return raw;   // 숫자가 아니면 손대지 않는다
        }
    }
}
