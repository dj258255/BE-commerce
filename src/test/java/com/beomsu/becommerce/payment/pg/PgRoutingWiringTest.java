package com.beomsu.becommerce.payment.pg;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 멀티 PG 라우팅 <b>배선</b> 검증 — 플래그를 켰을 때 정말로 {@link RoutingPgClient}가
 * {@code pgDelegate} 자리에 들어가는지 본다.
 *
 * <p>{@link RoutingPgClientTest}는 라우팅 <b>로직</b>(가중치 순 시도, failover, TIMEOUT 비-failover)을
 * 스텁으로 검증한다. 그건 "라우터가 제대로 동작한다"까지만 증명한다. 켰을 때 실제로 그 라우터가
 * 결제 경로에 꽂히는지는 <b>다른 질문</b>이고, 그동안 아무도 검증하지 않았다.
 *
 * <p>이걸 따로 두는 이유는 이 프로젝트에서 같은 실수를 세 번 했기 때문이다. 감사 로그 서비스가
 * 만들어져 있는데 호출부가 없었고, 비밀번호 이관 서비스도 배선 확인 없이 단위 테스트만 있었다.
 * 공통점은 <b>있는지</b>만 보고 <b>불리는지</b>를 안 봤다는 것이다.
 *
 * <p>여기서 깨질 수 있는 실패는 조용하다. 조건 표현식이 어긋나 두 후보가 동시에 살아나면
 * {@code pgDelegate} 주입이 모호해져 <b>기동이 실패</b>하고, 반대로 둘 다 죽으면 주입 대상이 없어
 * 역시 기동이 실패한다. 어느 쪽이든 장애 상황에서 이중화를 켜려는 순간에 발견하게 된다 —
 * 가장 나쁜 시점이다.
 */
class PgRoutingWiringTest {

    /** ResilientPgClient(@Qualifier("pgDelegate") 주입)까지 함께 올려 실제 주입 경로를 재현한다. */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PgRoutingConfig.class, FakePgClient.class, ResilientPgClient.class);

    @Test
    @DisplayName("라우팅 ON: pgDelegate 자리를 RoutingPgClient가 차지하고, FakePgClient 단독 등록은 사라진다")
    void routingEnabledWiresRoutingClient() {
        runner.withPropertyValues("app.pg.routing.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            // 후보가 정확히 하나여야 한다 — 둘이면 주입이 모호해지고, 없으면 주입 대상이 없다.
            assertThat(context.getBeansOfType(PgClient.class).values())
                    .filteredOn(c -> !(c instanceof ResilientPgClient))
                    .singleElement()
                    .isInstanceOf(RoutingPgClient.class);
            assertThat(context).hasSingleBean(ResilientPgClient.class);
        });
    }

    @Test
    @DisplayName("라우팅 OFF(기본): FakePgClient가 pgDelegate — 라우팅 설정 자체가 등록되지 않는다")
    void routingDisabledKeepsSingleProvider() {
        runner.withPropertyValues("app.pg.routing.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(PgClient.class).values())
                    .filteredOn(c -> !(c instanceof ResilientPgClient))
                    .singleElement()
                    .isInstanceOf(FakePgClient.class);
        });
    }

    @Test
    @DisplayName("프로퍼티 미설정도 OFF로 취급한다(matchIfMissing) — 기본값이 단일 PG여야 한다")
    void missingPropertyDefaultsToSingleProvider() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(PgClient.class).values())
                    .filteredOn(c -> !(c instanceof ResilientPgClient))
                    .singleElement()
                    .isInstanceOf(FakePgClient.class);
        });
    }

    @Test
    @DisplayName("라우팅 ON이면 경로가 2개이고 가중치 높은 쪽이 앞이다 — 시도 순서가 설정으로 정해진다")
    void routesAreOrderedByWeight() {
        runner.withPropertyValues("app.pg.routing.enabled=true").run(context -> {
            RoutingPgClient routing = context.getBeansOfType(PgClient.class).values().stream()
                    .filter(RoutingPgClient.class::isInstance)
                    .map(RoutingPgClient.class::cast)
                    .findFirst().orElseThrow();

            assertThat(routing.routes()).hasSize(2);
            assertThat(routing.routes().get(0).weight())
                    .isGreaterThan(routing.routes().get(1).weight());
        });
    }
}
