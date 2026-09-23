package com.beomsu.becommerce.assist.draft;

import org.springframework.stereotype.Component;

import java.text.NumberFormat;
import java.util.Locale;
import java.util.Optional;

/**
 * 기본 구현 — <b>외부 호출이 없다.</b> 사실을 정해진 틀에 옮겨 담기만 한다.
 *
 * <p>이게 "AI를 못 붙여서 만든 임시물"이 아니다. 두 가지 일을 한다.
 *
 * <p><b>하나, 기준선(baseline)</b>. 모델 어댑터가 붙었을 때
 * "그래서 얼마나 나아졌나"를 잴 상대가 필요하다. 기준선 없이 모델을 넣으면
 * 좋아 보이는 것과 좋아진 것을 구분할 수 없다.
 *
 * <p><b>둘, 폴백</b>. 모델이 죽거나 {@link NumericProvenanceGuard}에 걸려 초안이 없을 때
 * 상담원이 빈 화면을 보면 안 된다. 사실 나열만으로도 처음부터 조사하는 것보다는 낫다.
 *
 * <p><b>항상 빈으로 둔다.</b> {@code draft-provider} 가 무엇이든 이 구현이 필요하다.
 * 모델을 켜도 <b>대조군</b>으로 쓰이기 때문이다 — 블라인드 리뷰의 활성화 조건이
 * "편집률 중앙값이 <b>템플릿보다</b> 낮을 것"이라, 모델 초안과 같은 사실에서 같은 시점에
 * 템플릿 초안도 뽑아야 한다. provider 를 바꿔 두 번 돌리면 두 번째 회차는 리뷰어가
 * 이미 답을 아는 상태라 표본이 오염된다.
 *
 * <p>모델 어댑터가 켜지면 그쪽이 {@code @Primary} 라 {@link DraftPort} 주입은 그리로 간다.
 * 이 구현을 쓰려면 구체 타입으로 받는다.
 */
@Component
public class TemplateDraftAdapter implements DraftPort {

    private static final NumberFormat WON = NumberFormat.getIntegerInstance(Locale.KOREA);

    @Override
    public Optional<String> draft(FactPack facts) {
        if (facts.empty()) {
            return Optional.empty();     // 재료가 없으면 만들지 않는다
        }

        StringBuilder sb = new StringBuilder();
        sb.append("[주문 ").append(facts.orderNo()).append(" 확인 결과]\n\n");
        sb.append("문의하신 주문 건의 처리 내역을 확인했습니다.\n\n");

        for (String fact : facts.facts()) {
            sb.append("· ").append(fact).append('\n');
        }

        if (!facts.amounts().isEmpty()) {
            sb.append("\n확인된 금액: ");
            sb.append(facts.amounts().stream()
                    .sorted()
                    .map(a -> WON.format(a) + "원")
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("-"));
            sb.append('\n');
        }

        if (facts.causeHint() != null && !facts.causeHint().isBlank()) {
            sb.append("\n추정 원인(규칙 분류): ").append(facts.causeHint())
              .append("\n  ※ 제안일 뿐입니다. 확정은 담당자가 근거를 확인한 뒤 하십시오.\n");
        }

        if (!facts.complete()) {
            sb.append("\n※ 일부 시스템의 기록을 가져오지 못했습니다. ")
              .append("아래 내역이 전부가 아닐 수 있으니 확인 후 안내하십시오.\n");
        }

        sb.append("\n---\n")
          .append("이 초안은 시스템 기록을 옮긴 것입니다. ")
          .append("고객 발송 전 상담원이 내용을 확인하고 표현을 다듬으십시오.\n");

        return Optional.of(sb.toString());
    }

    @Override
    public String name() {
        return "template";
    }
}
