package com.beomsu.becommerce.assist.web;

import com.beomsu.becommerce.assist.residual.ResidualSuggestionLog;
import com.beomsu.becommerce.assist.residual.ResidualSuggestion;
import com.beomsu.becommerce.assist.residual.ResidualCauseService;
import com.beomsu.becommerce.assist.residual.ResidualCauseService;
import com.beomsu.becommerce.assist.residual.ResidualSuggestion;
import com.beomsu.becommerce.assist.residual.ResidualSuggestionLog;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 규칙이 못 가른 건의 원인 후보를 화면에 준다.
 *
 * <p><b>왜 대사 어드민이 아니라 여기인가.</b> {@code reconciliation} 모듈의
 * {@code allowedDependencies}는 {@code shared·payment·audit}이고, {@code assist}가
 * {@code reconciliation}을 의존한다. 확정 화면 쪽에서 이걸 부르면 <b>순환</b>이 된다.
 * 그래서 창구를 assist 쪽에 두고 <b>화면이 두 번 부른다.</b> 왕복이 하나 늘지만
 * 경계가 유지되고, 규칙 제안만으로 충분한 건에서는 이 호출 자체가 안 나간다.
 *
 * <p>같은 이유로 {@code CsDraftAdminController}도 여기 있다. 상담 초안 역시
 * assist 가 만들고 대사 화면이 가져다 쓴다.
 *
 * <p><b>후보일 뿐이다.</b> 이 응답으로 확정하지 않는다. 화면은 사람이 고르는 목록에
 * 항목 하나를 더 얹고, 어디서 온 값인지 함께 보여줘야 한다. 규칙이 낸 것과
 * 모델이 낸 것이 화면에서 같아 보이면 사람이 둘을 같은 무게로 읽는다.
 */
@RestController
@RequestMapping("/api/v1/admin/orders")
class ResidualCauseAdminController {

    private final ResidualCauseService service;
    private final ResidualSuggestionLog suggestions;

    /**
     * 이 비율만큼은 후보를 만들되 <b>화면에 안 준다</b>. 비교군을 만들기 위해서다.
     *
     * <p>제안을 보여준 건만 모으면 "제안 덕분에 빨라졌다"와 "원래 그 정도였다"를 가를 수
     * 없다. 전부 감추면 기능이 죽고, 전부 보여주면 비교군이 없다. 기본 20%로 둔다.
     */
    private final int blindPercent;

    ResidualCauseAdminController(ResidualCauseService service, ResidualSuggestionLog suggestions,
                                 @Value("${app.assist.residual.blind-percent:20}") int blindPercent) {
        this.service = service;
        this.suggestions = suggestions;
        this.blindPercent = blindPercent;
    }

    /**
     * 잔여 원인 후보.
     *
     * <p>기권했거나 가드에 걸렸으면 <b>200에 {@code suggested=false}</b>로 준다.
     * 404를 주면 화면이 "없음"과 "실패"를 구분 못 한다.
     *
     * @param rulesDecided 규칙 분류기가 이미 후보를 냈는지. 화면이 알려준다 —
     *                     냈으면 모델을 부르지 않는다(가드 1)
     */
    @GetMapping("/{orderNo}/residual-cause")
    ResidualView residualCause(@PathVariable String orderNo,
                               @RequestParam Long reconResultId,
                               @RequestParam(defaultValue = "false") boolean rulesDecided) {
        Optional<ResidualSuggestion> s = service.suggest(
                orderNo, reconResultId,
                rulesDecided ? List.of(RULES_DECIDED_MARKER) : List.of());

        // blind 표본은 후보를 만들어 <기록만> 하고 화면에는 안 준다.
        // 확정 시각과 맞춰 보면 제안이 실제로 일을 줄였는지가 여기서 갈린다.
        boolean shown = ThreadLocalRandom.current().nextInt(100) >= blindPercent;
        suggestions.record(reconResultId, s.map(ResidualSuggestion::cause).orElse(null), shown);

        if (s.isEmpty() || !shown) {
            return new ResidualView(false, null, null, 0);
        }
        ResidualSuggestion v = s.get();
        return new ResidualView(true, v.cause().name(), v.rationale(), v.confidence());
    }

    /**
     * 가드 1을 태우기 위한 표식. 내용은 안 본다 — 비어 있지 않기만 하면 된다.
     *
     * <p>화면이 규칙 제안 목록을 통째로 되보내게 하지 않으려는 것이다. 되보내면
     * <b>검증의 기준값을 클라이언트에서 받는</b> 꼴이 되고, 그건 이 프로젝트가
     * 1편에서 잡은 실수다.
     */
    private static final com.beomsu.becommerce.reconciliation.CauseSuggestion RULES_DECIDED_MARKER =
            com.beomsu.becommerce.reconciliation.CauseSuggestion.likely(
                    com.beomsu.becommerce.reconciliation.ResolveCause.OTHER, "규칙이 이미 후보를 냈다");

    /**
     * @param source 어디서 온 값인지 화면이 표시할 수 있게 항상 실어 준다
     */
    record ResidualView(boolean suggested, String cause, String rationale, int confidence,
                        String source) {
        ResidualView(boolean suggested, String cause, String rationale, int confidence) {
            this(suggested, cause, rationale, confidence, "model");
        }
    }
}
