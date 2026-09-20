package com.beomsu.becommerce.payment.pg;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * <b>결제창을 띄우기 전에</b> 어느 PG로 갈지 고른다.
 *
 * <p><b>왜 승인 시점이 아니라 여기인가.</b> 결제창 방식에서 승인에 쓰는 결제 키는 고객이
 * 그 PG의 결제창에서 인증을 마쳐야 발급된다(업계 용어로 PSP 토큰). 그 키를 다른 PG에 보내면
 * 모르는 거래라고 답하므로 <b>승인 단계에서 넘기면 성공률이 0이다.</b> 실 연동에서 확인했다.
 *
 * <p>그래서 현업은 고르는 시점을 앞으로 옮긴다. 포트원 스마트 라우팅이 개별 채널 대신
 * {@code channelGroupId}를 받아 <b>결제 요청 시점에</b> 채널 비율로 고르는 것이 같은 구조이고,
 * 결제 오케스트레이션 일반도 "실제 거래가 제출되기 전에" 가용 공급자를 평가한다.
 *
 * <p><b>진행 중인 거래는 여전히 넘기지 않는다.</b> 이건 아픈 PG를 <b>애초에 안 고르는</b>
 * 장치이지, 응답을 못 받은 요청을 다른 곳으로 다시 보내는 것이 아니다. 타임아웃을
 * failover 하지 않는다는 원칙과 충돌하지 않는다.
 *
 * <p>고르는 기준은 둘이다. <b>차단기가 열린 경로는 뺀다</b>(그 PG는 지금 실패율이 임계를 넘었다).
 * 남은 것 중에서 <b>가중치에 비례해</b> 고른다. 전부 열려 있으면 그래도 하나는 골라 준다.
 * 결제를 아예 못 하게 만드는 것보다 낫고, 어차피 승인 단계에서 다시 판정한다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.pg.routing.enabled", havingValue = "true")
public class PgSelector {

    private final List<RoutingPgClient.PgRoute> routes;

    PgSelector(RoutingPgClient routing) {
        this.routes = routing.routes();
    }

    /**
     * 지금 결제를 시작하기에 가장 나은 PG를 고른다.
     *
     * @return 고른 PG 이름. 경로가 하나도 없으면 {@link Optional#empty()}
     */
    public Optional<String> select() {
        if (routes.isEmpty()) {
            return Optional.empty();
        }
        List<RoutingPgClient.PgRoute> healthy = routes.stream()
                .filter(r -> r.circuitBreaker().getState() != CircuitBreaker.State.OPEN)
                .toList();

        if (healthy.isEmpty()) {
            // 전부 아프다. 그래도 하나는 준다 — 여기서 막으면 결제가 전면 중단된다.
            log.warn("[pg-select] 모든 경로의 차단기가 열림. 첫 경로로 진행 route={}", routes.getFirst().name());
            return Optional.of(routes.getFirst().name());
        }
        if (healthy.size() < routes.size()) {
            log.info("[pg-select] 차단기가 열린 경로를 제외함 남은={}", healthy.stream().map(RoutingPgClient.PgRoute::name).toList());
        }
        return Optional.of(pickWeighted(healthy));
    }

    /** 가중치에 비례해 하나를 고른다. 가중치가 전부 0이면 첫 경로. */
    private String pickWeighted(List<RoutingPgClient.PgRoute> candidates) {
        int total = candidates.stream().mapToInt(RoutingPgClient.PgRoute::weight).sum();
        if (total <= 0) {
            return candidates.getFirst().name();
        }
        int dice = ThreadLocalRandom.current().nextInt(total);
        for (RoutingPgClient.PgRoute r : candidates) {
            dice -= r.weight();
            if (dice < 0) {
                return r.name();
            }
        }
        return candidates.getFirst().name();
    }
}
