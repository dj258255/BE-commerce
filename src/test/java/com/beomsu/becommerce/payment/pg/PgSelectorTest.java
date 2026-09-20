package com.beomsu.becommerce.payment.pg;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PgSelector} — 결제창을 띄우기 전에 고르는 규칙.
 *
 * <p><b>이 검사가 지켜야 하는 것은 하나다.</b> 아픈 PG로 고객을 보내지 않는다.
 * 그리고 <b>지키지 말아야 할 것</b>도 하나 있다. 전부 아파도 결제를 막지는 않는다.
 */
class PgSelectorTest {

    private PgSelector selectorOf(RoutingPgClient.PgRoute... routes) {
        return new PgSelector(new RoutingPgClient(List.of(routes)));
    }

    private RoutingPgClient.PgRoute route(String name, int weight) {
        return RoutingPgClient.PgRoute.of(name, new FakePgClient(), weight);
    }

    /** 실패를 임계까지 밀어넣어 차단기를 연다. */
    private void trip(RoutingPgClient.PgRoute r) {
        CircuitBreaker cb = r.circuitBreaker();
        for (int i = 0; i < 5; i++) {
            cb.onError(0, java.util.concurrent.TimeUnit.MILLISECONDS, new PgUnreachableException("죽음", new java.io.IOException("connect refused")));
        }
    }

    @Test
    @DisplayName("차단기가 열린 PG는 후보에서 뺀다")
    void skipsOpenCircuit() {
        var sick = route("sick", 100);
        var healthy = route("healthy", 1);
        trip(sick);

        PgSelector selector = selectorOf(sick, healthy);

        // 가중치가 100대 1이어도 아픈 쪽은 안 고른다
        for (int i = 0; i < 30; i++) {
            assertThat(selector.select()).contains("healthy");
        }
    }

    @Test
    @DisplayName("전부 아파도 결제를 막지는 않는다")
    void neverBlocksCheckout() {
        var a = route("a", 10);
        var b = route("b", 5);
        trip(a);
        trip(b);

        // 여기서 빈 값을 주면 PG 둘이 동시에 흔들릴 때 결제가 전면 중단된다.
        // 어차피 승인 단계에서 다시 판정하므로, 고르기는 한다.
        assertThat(selectorOf(a, b).select()).isPresent();
    }

    @Test
    @DisplayName("건강하면 가중치에 비례해 고른다")
    void picksByWeight() {
        PgSelector selector = selectorOf(route("big", 9), route("small", 1));

        long big = IntStream.range(0, 600)
                .mapToObj(i -> selector.select())
                .flatMap(Optional::stream)
                .filter("big"::equals).count();

        // 9:1 이라 대략 540 근처. 난수라 폭을 넉넉히 둔다.
        assertThat(big).isBetween(450L, 590L);
    }

    @Test
    @DisplayName("경로가 하나면 그것만 고른다")
    void singleRoute() {
        assertThat(selectorOf(route("only", 1)).select()).contains("only");
    }
}
