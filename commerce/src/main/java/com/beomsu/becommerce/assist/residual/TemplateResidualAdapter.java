package com.beomsu.becommerce.assist.residual;

import com.beomsu.becommerce.assist.draft.FactPack;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 모델이 없을 때의 기본 구현. <b>항상 기권한다.</b>
 *
 * <p>규칙이 못 가른 건은 어려운 건이라 템플릿이 낼 수 있는 답이 없다. 상담 초안에서는
 * 템플릿이 문장을 조립할 수 있었지만, 여기서는 조립할 근거 자체가 없다.
 *
 * <p>그래도 빈 구현을 두는 이유는 {@link ResidualCauseService}가 포트 없이도 돌아야
 * 하기 때문이다. 모델을 안 켠 환경에서 이 기능이 예외를 던지면 확정 경로가 막힌다.
 */
@Component
@ConditionalOnProperty(name = "app.assist.residual-provider", havingValue = "template",
        matchIfMissing = true)
public class TemplateResidualAdapter implements ResidualCausePort {

    @Override
    public Optional<ResidualSuggestion> suggest(FactPack facts) {
        return Optional.empty();
    }

    @Override
    public String name() {
        return "template";
    }
}
