package com.beomsu.becommerce.recommendation.web;

import com.beomsu.becommerce.recommendation.internal.SyntheticAvailability;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * E4 실험 계기 — <b>생성 중에 사실이 바뀌는 상황</b>을 주입한다.
 *
 * <p>E4의 질문은 "확인을 언제 하는가"이고, 그 질문이 성립하려면 <b>확인과 응답 사이에 사실이
 * 바뀌어야</b> 한다. 실제 재고로 그 변화를 만들려면 누군가 실제로 사야 하고, 그 속도를 실험이
 * 정할 수 없으면 위반율이 우연에 좌우된다. 그래서 변화를 <b>부하 생성기가 직접 주입</b>한다 —
 * 다른 사용자가 동시에 사는 상황을 흉내 내는 것이다.
 *
 * <p><b>{@code @ConditionalOnProperty}로 잠가 둔다(기본 off).</b> 이 엔드포인트는 상품의 가용성을
 * 요청으로 바꾼다 — 실험 밖에서 열려 있으면 그 자체가 결함이다. 빈이 아예 만들어지지 않으므로
 * 기본 프로파일에서는 경로가 404다. 인증은 필요하다(로그인 사용자만).
 *
 * <p><b>한계를 숨기지 않는다</b>: 여기서 소진시키는 것은 <b>합성 가용성</b>이지 실제 재고가 아니다.
 * 실제 재고였다면 주문·결제 경로를 지나야 하고 이 엔드포인트는 존재할 이유가 없다.
 */
@RestController
@RequestMapping("/api/v1/experiments/constraint")
@ConditionalOnProperty(name = "app.recommendation.experiment.enabled", havingValue = "true")
public class ConstraintExperimentController {

    private final SyntheticAvailability availability;

    public ConstraintExperimentController(SyntheticAvailability availability) {
        this.availability = availability;
    }

    /** 아직 팔 수 있는 것 하나를 소진시킨다 — "누군가 마지막 재고를 샀다". */
    @PostMapping("/consume")
    public Map<String, Object> consume() {
        long consumed = availability.consumeAny();
        return Map.of("changed", consumed, "unavailableCount", availability.unavailableCount(),
                "version", availability.version());
    }

    /**
     * 품절된 것 하나를 되돌린다 — "입고됐다".
     *
     * <p>하네스가 {@code consume}·{@code release}를 짝지어 돌린다. 소진만 하면 풀이 몇 초 만에
     * 전부 품절되어 그 뒤로는 <b>아무것도 안 바뀐다</b> — 그러면 위반율 0이 "확인이 잘 해서"인지
     * "바뀐 게 없어서"인지 구분되지 않는다.
     */
    @PostMapping("/release")
    public Map<String, Object> release() {
        long released = availability.releaseAny();
        return Map.of("changed", released, "unavailableCount", availability.unavailableCount(),
                "version", availability.version());
    }

    /** 전부 되돌린다(런 사이 초기화). */
    @PostMapping("/restock")
    public Map<String, Object> restock() {
        availability.restockAll();
        return Map.of("unavailableCount", availability.unavailableCount(), "version", availability.version());
    }

    /** 지금 상태 — 하네스가 위반을 <b>바깥에서</b> 확인할 때도 쓴다. */
    @GetMapping("/state")
    public Map<String, Object> state() {
        return Map.of("pool", availability.knownItemIds(),
                "unavailableCount", availability.unavailableCount(),
                "version", availability.version());
    }

    /** 풀 전체 — 하네스가 활동으로 넣을 id를 여기서 받아 간다(풀이 갈라지지 않게). */
    @GetMapping("/pool")
    public List<Long> pool() {
        return availability.knownItemIds();
    }
}
