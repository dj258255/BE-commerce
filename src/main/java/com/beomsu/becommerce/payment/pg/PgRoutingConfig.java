package com.beomsu.becommerce.payment.pg;

import com.beomsu.becommerce.payment.internal.Payment;
import com.beomsu.becommerce.payment.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 멀티 PG 라우팅 배선 — {@code app.pg.routing.enabled=true}일 때만 활성화(기본 off).
 *
 * <p>기본(단일 PG) 모드에서는 {@link FakePgClient}/{@code TossPgClient}가 {@code @Qualifier("pgDelegate")}로
 * 직접 위임 대상이 된다. 라우팅을 켜면 이 config가 대신 {@link RoutingPgClient}를 {@code pgDelegate}로
 * 등록한다 — 그러면 계층이 이렇게 합성된다:
 *
 * <pre>
 *   PaymentService → ResilientPgClient(@Primary, 외곽 서킷·query 재시도)
 *                  → RoutingPgClient(pgDelegate, PG별 서킷·failover)
 *                  → [primary FakePg, secondary FakePg]
 * </pre>
 *
 * <p>데모 경로는 가중치가 다른 FakePg 2개다(운영이라면 Toss·백업 PG 어댑터를 경로로 둔다). 가중치가
 * 높은 primary부터 시도하고, primary가 <b>장애</b>(예외/서킷 오픈)면 secondary로 failover한다. 단
 * TIMEOUT(미확정)은 이중결제 위험 때문에 failover하지 않는다({@link RoutingPgClient} 참고).
 *
 * <p><b>취소·조회는 원 결제를 처리한 PG로만 간다.</b> 승인 결과에 어느 PG가 처리했는지를 실어
 * {@code Payment.pgProvider}에 적고, 취소와 조회가 그 값을 목적지로 쓴다. 다른 PG에 보내면
 * "그런 거래 없음"이 <b>정상 응답</b>으로 돌아오고 그것이 그대로 취소 결과가 된다 — 고객 돈은 원 PG에
 * 잡혀 있는데 우리 장부에는 취소로 남는다. 요청이 닿았으므로 failover 조건도 아니다.
 *
 * <p>승인 PG가 경로에 없으면 아무 데도 보내지 않는다. 설정에서 빠졌거나 이름이 바뀐 경우인데,
 * 그때는 사람이 봐야 한다. 조회는 확정하지 않고 다음 주기로 넘긴다.
 */
@Configuration
@ConditionalOnProperty(name = "app.pg.routing.enabled", havingValue = "true")
public class PgRoutingConfig {

    private static final Logger log = LoggerFactory.getLogger(PgRoutingConfig.class);

    @Bean
    @Qualifier("pgDelegate")
    PgClient routingPgDelegate() {
        RoutingPgClient routing = new RoutingPgClient(List.of(
                RoutingPgClient.PgRoute.of("primary-fake", new FakePgClient(), 10),
                RoutingPgClient.PgRoute.of("secondary-fake", new FakePgClient(), 5)));
        log.info("멀티 PG 라우팅 활성화 — 경로 {}개 (가중치 순 시도, 장애 시 failover)",
                routing.routes().size());
        return routing;
    }
}
