package com.beomsu.becommerce.assist.narrative;

import com.beomsu.becommerce.assist.draft.FactPack;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 기본 구현 — <b>모델 없이</b> 사실을 그대로 잇는다.
 *
 * <p>모델이 없을 때 화면이 비지 않게 하고, 동시에 <b>모델이 넘어야 할 자(尺)</b>가 된다.
 * 모델의 서술이 이것보다 나은지를 재지 않으면 "붙였다"만 남는다 — 잔여 후보에서 그걸로 한 번 데었다.
 *
 * <p>일부러 잘 쓰지 않는다. 시간순으로 잇기만 한다. 이 문장이 이미 충분히 읽을 만하면
 * <b>모델이 필요 없다는 뜻</b>이고, 그것도 답이다.
 */
@Component
@ConditionalOnProperty(name = "app.assist.narrative-provider", havingValue = "template",
        matchIfMissing = true)
public class TemplateNarrativeAdapter implements TimelineNarrativePort {

    @Override
    public Optional<String> narrate(FactPack facts) {
        if (facts == null || facts.facts().isEmpty()) {
            return Optional.empty();
        }
        StringBuilder sb = new StringBuilder("주문 ").append(facts.orderNo()).append(" — ");
        sb.append(String.join(" 그다음 ", facts.facts()));
        sb.append('.');
        if (!facts.complete()) {
            sb.append(" 일부 출처 조회가 실패해 이 목록은 불완전하다.");
        }
        return Optional.of(sb.toString());
    }

    @Override
    public String name() {
        return "template";
    }
}
