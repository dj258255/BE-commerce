package com.beomsu.becommerce.order;

import com.beomsu.becommerce.order.catalog.Stock;
import com.beomsu.becommerce.order.catalog.StockRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E4 변화 주입기의 <b>핵심 불변식</b>을 고정한다 — <b>교체는 품절 집합 크기를 바꾸지 않는다.</b>
 *
 * <p>이것이 ①의 전부다. 이전 구현은 소진·해제를 따로 돌려 같은 항목이 왕복했고, 그래서 품절 집합
 * <b>크기가 통제되지 않았다</b>. 그 결과 {@code NONE} 의 위반율이 정책이 아니라 크기를 따라가 순서가
 * 뒤집혔다(실측 0.96% ↔ 7.11%, E4 리포트 「틀렸던 것」 ①). 크기가 K 로 고정되면 그 혼입이 사라진다.
 *
 * <p>H2 인메모리 + Hibernate DDL 로 돈다 — 스키마가 아니라 <b>집합 크기</b>만 보므로 MySQL 이 필요 없다.
 * Flyway 는 끈다(마이그레이션은 MySQL 전용). 테스트는 트랜잭션 안에서 돌고 끝나면 롤백된다.
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@ContextConfiguration(classes = ExperimentStockChurnTest.TestApp.class)
@DisplayName("E4 변화 주입 — 교체는 품절 집합 크기를 고정한다")
class ExperimentStockChurnTest {

    private static final List<Long> POOL = List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);

    @Autowired
    StockRepository stockRepository;

    private ExperimentStockChurn churn;
    private StockAvailabilityFacts facts;

    @BeforeEach
    void setUp() {
        churn = new ExperimentStockChurn(stockRepository);
        facts = new StockAvailabilityFacts(stockRepository);
    }

    @Test
    @DisplayName("prime 은 품절을 정확히 K 개로 세운다(결정적: 풀 앞에서 K 개)")
    void primeSetsExactlyK() {
        int k = churn.primeTo(POOL, 4);

        assertThat(k).isEqualTo(4);
        assertThat(unavailable()).containsExactlyInAnyOrder(1L, 2L, 3L, 4L);
    }

    @Test
    @DisplayName("교체를 반복해도 크기는 K 로 고정된다 — 통제된 변수")
    void swapKeepsSizeConstant() {
        churn.primeTo(POOL, 3);

        for (int i = 0; i < 100; i++) {
            churn.swapOne(POOL, 3);
            assertThat(unavailable())
                    .as("교체 %d회차 — 크기가 흔들리면 NONE 의 위반율이 정책이 아니라 크기를 따라간다", i + 1)
                    .hasSize(3);
        }
    }

    @Test
    @DisplayName("교체는 구성을 실제로 돌린다 — 크기만 고정하고 멈추면 위반율이 정렬을 재는 값이 된다")
    void swapRotatesComposition() {
        churn.primeTo(POOL, 3);

        Set<Set<Long>> seen = new HashSet<>();
        Set<Long> everSoldOut = new HashSet<>();
        for (int i = 0; i < POOL.size() * 4; i++) {
            churn.swapOne(POOL, 3);
            Set<Long> now = unavailable();
            seen.add(now);
            everSoldOut.addAll(now);
        }

        // 첫 구현은 같은 항목을 왕복시켜 창이 사실상 고정됐고, 그래서 NONE 이 응답마다 정확히 같은
        // 위반 수(6.00)를 냈다 — 그 회귀를 여기서 막는다. 풀의 모든 항목이 언젠가 품절이 되어야
        // "어떤 것이 품절인가"가 계속 바뀐다고 말할 수 있다.
        assertThat(everSoldOut)
                .as("창이 풀 전체를 돌아야 한다 — 왕복하면 구성이 고정된다")
                .containsExactlyInAnyOrderElementsOf(POOL);
        assertThat(seen.size()).as("창 모양이 여러 가지로 바뀌어야 한다").isGreaterThan(2);
    }

    @Test
    @DisplayName("전부 품절이면 교체할 것이 없다 — 크기를 늘리지 않고 아무 것도 안 한다")
    void swapDoesNothingWhenAllSoldOut() {
        churn.primeTo(POOL, POOL.size());

        ExperimentStockChurn.Swap swapped = churn.swapOne(POOL, POOL.size());

        assertThat(swapped.released()).isEqualTo(-1L);
        assertThat(swapped.consumed()).isEqualTo(-1L);
        assertThat(unavailable()).hasSize(POOL.size());
    }

    @Test
    @DisplayName("재고 행이 없어도 restock 이 만든다 — 시드가 안 돌아간 환경에서도 실험이 선다")
    void restockCreatesMissingRows() {
        assertThat(stockRepository.findByProductIdIn(List.of(99L))).isEmpty();

        churn.restockAll(List.of(99L, 100L));

        assertThat(stockRepository.findByProductIdIn(List.of(99L, 100L)))
                .extracting(Stock::getProductId)
                .containsExactlyInAnyOrder(99L, 100L);
        assertThat(facts.unavailableAmong(List.of(99L, 100L)))
                .as("새로 만든 행은 입고 수량이라 팔 수 있어야 한다")
                .isEmpty();
    }

    /** 지금 품절인 id — 확인 포트(실제 재고 읽기)로 읽는다. */
    private Set<Long> unavailable() {
        return new HashSet<>(facts.unavailableAmong(POOL));
    }

    @Configuration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = Stock.class)
    @EnableJpaRepositories(basePackageClasses = StockRepository.class)
    static class TestApp {
    }
}
