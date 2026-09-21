package com.beomsu.becommerce.recommendation.web;

import com.beomsu.becommerce.order.ExperimentStockChurn;
import com.beomsu.becommerce.recommendation.internal.ItemPool;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
 * <p><b>무엇이 바뀌었나(E4 후속)</b>: 이전에는 메모리 합성 집합을 고쳤다. 이제 <b>실제 stock 행</b>을
 * 고친다({@link ExperimentStockChurn} — 재고의 소유자 order 가 바꾼다). 그래서 제약 <b>확인이 DB
 * 왕복</b>이 되고, 확인 시점의 교환비가 처음으로 숫자로 나온다.
 *
 * <p><b>왜 소진·해제를 따로 열지 않는가</b>: 그 둘을 따로 부르면 같은 항목이 왕복해 품절 집합
 * <b>구성이 고정</b>되고, 그 상태에서 잰 {@code NONE} 의 위반율은 정책 효과가 아니라 고정된 정렬을
 * 잰 값이 된다(실제로 그렇게 나왔다 — 응답마다 정확히 6.00). 그래서 실험이 쓸 수 있는 변화는
 * <b>크기를 보존하는 교체({@code /swap})와 목표 세우기({@code /prime})</b> 뿐이다.
 *
 * <p><b>{@code @ConditionalOnProperty}로 잠가 둔다(기본 off).</b> 이 엔드포인트는 상품의 가용성을
 * 요청으로 바꾼다 — 실험 밖에서 열려 있으면 그 자체가 결함이다. 빈이 아예 만들어지지 않으므로
 * 기본 프로파일에서는 경로가 404다. 인증은 필요하다(로그인 사용자만).
 *
 * <p><b>한계를 숨기지 않는다</b>: 바꾸는 대상은 <b>실험용으로 심은 재고 행</b>이다(products 행이 없어
 * 상점에는 안 보인다). 그래도 읽기·쓰기가 실제 테이블을 지나므로 <b>비용은 실제 비용</b>이다.
 */
@RestController
@RequestMapping("/api/v1/experiments/constraint")
@ConditionalOnProperty(name = "app.recommendation.experiment.enabled", havingValue = "true")
public class ConstraintExperimentController {

    private final ExperimentStockChurn churn;

    public ConstraintExperimentController(ExperimentStockChurn churn) {
        this.churn = churn;
    }

    private static List<Long> pool() {
        return ItemPool.experimentPool();
    }

    /**
     * <b>품절 집합을 목표 크기로 유지한 채 구성을 한 칸 돌린다</b> — 실험의 기본 변화 주입이다.
     *
     * <p>입고 하나 + 소진 하나(net 0)라 크기가 K 로 고정된 채 "어떤 것이 품절인가"만 계속 바뀐다.
     * 응답의 {@code unavailableCount} 가 K 인지가 <b>런의 유효 조건</b>이다.
     */
    @PostMapping("/swap")
    public Map<String, Object> swap(@RequestParam(name = "count", defaultValue = "6") int count) {
        ExperimentStockChurn.Swap swap = churn.swapOne(pool(), count);
        return Map.of("changed", swap.changed(),
                "released", swap.released(), "consumed", swap.consumed(),
                "unavailableCount", churn.unavailableCount(pool()), "version", churn.version());
    }

    /** 목표 K까지 품절을 세운다(결정적: 풀 앞에서 K개). 런 시작 시 하네스가 부른다. */
    @PostMapping("/prime")
    public Map<String, Object> prime(@RequestParam(name = "count", defaultValue = "6") int count) {
        int target = churn.primeTo(pool(), count);
        return Map.of("changed", target, "unavailableCount", churn.unavailableCount(pool()),
                "version", churn.version());
    }

    /** 전부 되돌린다(런 사이 초기화). */
    @PostMapping("/restock")
    public Map<String, Object> restock() {
        churn.restockAll(pool());
        return Map.of("unavailableCount", churn.unavailableCount(pool()), "version", churn.version());
    }

    /** 지금 상태 — 하네스가 품절 집합 크기를 <b>통제</b>하고 있는지 여기서 본다. */
    @GetMapping("/state")
    public Map<String, Object> state() {
        return Map.of("pool", pool(),
                "unavailableCount", churn.unavailableCount(pool()),
                "version", churn.version());
    }

    /** 풀 전체 — 하네스가 활동으로 넣을 id를 여기서 받아 간다(풀이 갈라지지 않게). */
    @GetMapping("/pool")
    public List<Long> poolEndpoint() {
        return pool();
    }
}
