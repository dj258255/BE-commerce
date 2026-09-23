package com.beomsu.becommerce.payment.pg;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 개발/테스트용 가짜 PG. 실제 PG 키·네트워크 없이 결제 플로우를 완주하고, Phase 2의 실패 시나리오를
 * 재현한다.
 *
 * <p>상태를 가진다: {@code approve}가 호출되면 PG 측 상태({@link #pgSideStatusOnApprove})를 기록해 두고,
 * 이후 {@link #query}가 그 값을 돌려준다. 이렇게 하면 <b>"우리는 타임아웃이었지만 PG에는 승인으로
 * 남아 있는"</b> 상황(복구 배치가 승인으로 확정)과 <b>"PG에 아예 없는"</b> 상황(망취소/실패)을 모두
 * 테스트할 수 있다.
 *
 * <p><b>pgDelegate 역할은 단일 PG 모드에서만</b>: 멀티 PG 라우팅({@code app.pg.routing.enabled=true})을
 * 켜면 {@link RoutingPgClient}가 {@code @Qualifier("pgDelegate")} 자리를 대신 차지한다. 같은 qualifier가
 * 둘이면 주입이 모호해지므로, 라우팅이 켜지면 이 빈은 등록되지 않는다(라우팅 config가 자체 FakePg
 * 인스턴스로 경로를 구성한다).
 */
@Component
@Qualifier("pgDelegate")
@Profile("!prod")
@ConditionalOnProperty(name = "app.pg.routing.enabled", havingValue = "false", matchIfMissing = true)
public class FakePgClient implements PgClient {

    /** 다음 approve 호출이 <b>우리에게</b> 돌려줄 결과 (SUCCESS/FAILED/TIMEOUT) */
    private final AtomicReference<PgApproveResult> nextApproveResult =
            new AtomicReference<>(PgApproveResult.success("CARD"));

    /** approve 시 <b>PG 측에</b> 남길 실제 상태 (query가 이 값을 돌려준다) */
    private final AtomicReference<PgPaymentStatus> pgSideStatusOnApprove =
            new AtomicReference<>(PgPaymentStatus.APPROVED);

    private final Map<String, PgPaymentStatus> pgSide = new ConcurrentHashMap<>();

    /**
     * 다음 approve 가 붙일 카드 지문. <b>기본은 null 이다.</b>
     *
     * <p>여기서 아무 값이나 기본으로 깔면 로컬의 모든 결제가 <b>같은 카드 한 장</b>이 된다.
     * 그러면 속도 규칙과 창 건수가 계속 걸려 규칙 성적이 표본이 아니라 설정 탓으로 흔들린다.
     * null 이면 사후 탐지가 예전처럼 {@code paymentKey} 로 떨어져 지금 동작이 그대로 유지된다.
     * 카드 단위로 묶이는 흐름을 재현할 때만 {@link #setNextCardFingerprint} 로 지정한다.
     */
    private final AtomicReference<String> nextCardFingerprint = new AtomicReference<>();

    public void setNextResult(PgApproveResult result) {
        nextApproveResult.set(result);
    }

    /**
     * 다음 승인부터 이 지문을 붙인다. 같은 값을 여러 결제에 주면 <b>한 카드가 여러 번 결제한
     * 흐름</b>이 되어 창 건수·금액 계단·과거 중앙값 대비 배수가 살아난다.
     *
     * @param cardKey 카드를 가리키는 아무 문자열. 실제 PG 가 주는 마스킹 번호 대신 쓰는 대역이다
     */
    public void setNextCardFingerprint(String cardKey) {
        nextCardFingerprint.set(cardKey == null ? null : CardFingerprint.of(cardKey, "FAKE"));
    }

    /** approve 시 PG 측에 남길 실제 상태를 지정한다 (타임아웃인데 실제론 승인된 상황 재현용). */
    public void setPgSideStatusOnApprove(PgPaymentStatus status) {
        pgSideStatusOnApprove.set(status);
    }

    public void reset() {
        nextApproveResult.set(PgApproveResult.success("CARD"));
        pgSideStatusOnApprove.set(PgPaymentStatus.APPROVED);
        nextCardFingerprint.set(null);
        pgSide.clear();
    }

    /**
     * 승인 응답을 이만큼 늦춘다(ms). 0이면 지금 동작 그대로다.
     *
     * <p>ADR-022의 실험용 스위치다. 실 PG({@code TossPgClient})에는 지연을 주입할 수 없고,
     * 앱↔PG 사이에 프록시를 세우는 것보다 여기서 늦추는 쪽이 재현이 결정적이다.
     * 느린 PG가 워커 스레드를 얼마나 오래 잡는지, 그때 자원이 어디서 먼저 마르는지를 잰다.
     */
    private final AtomicLong approveLatencyMillis = new AtomicLong(0);

    /**
     * 이 시간을 넘겨 늦어지면 실 클라이언트처럼 끊고 TIMEOUT을 돌려준다.
     *
     * <p><b>PG 측 상태는 이미 남긴 뒤</b>에 끊는다. 그래야 "PG에는 승인으로 남아 있는데 우리는
     * 미확정"이라는 ADR-007의 그 상황이 재현된다. 타임아웃 값을 줄이면 미확정이 얼마나 늘어나는지가
     * ADR-022 선택지 C의 비용이다.
     */
    private final AtomicLong readTimeoutMillis = new AtomicLong(5_000);

    @Value("${payment.fake-pg.approve-latency-ms:0}")
    public void setApproveLatencyMillis(long millis) {
        approveLatencyMillis.set(millis);
    }

    @Value("${payment.fake-pg.read-timeout-ms:5000}")
    public void setReadTimeoutMillis(long millis) {
        readTimeoutMillis.set(millis);
    }

    @Override
    public PgApproveResult approve(PgApproveCommand command) {
        // 우리에게 무엇을 돌려주든(성공/타임아웃), PG 측에는 지정된 상태를 남긴다.
        pgSide.put(command.paymentKey(), pgSideStatusOnApprove.get());

        PgApproveResult timedOut = sleepForInjectedLatency();
        if (timedOut != null) {
            return timedOut;
        }

        PgApproveResult result = nextApproveResult.get();
        String card = nextCardFingerprint.get();
        // 지문은 승인 성공에만 붙인다. 실패·미확정에 붙이면 승인 안 난 건이 창에 들어간다.
        if (card == null || result.outcome() != PgOutcome.SUCCESS) {
            return result;
        }
        return new PgApproveResult(result.outcome(), result.method(), result.failReason(),
                result.provider(), card);
    }

    /**
     * 주입된 지연만큼 기다린다. 타임아웃을 넘기면 그 시점에 끊고 TIMEOUT을 돌려준다.
     *
     * @return 끊겼으면 TIMEOUT 결과, 정상이면 null
     */
    private PgApproveResult sleepForInjectedLatency() {
        long latency = approveLatencyMillis.get();
        if (latency <= 0) {
            return null;
        }
        long timeout = readTimeoutMillis.get();
        boolean cut = latency > timeout;
        try {
            Thread.sleep(cut ? timeout : latency);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return PgApproveResult.timeout("주입 지연 대기 중 인터럽트");
        }
        return cut ? PgApproveResult.timeout("주입 지연이 read-timeout 초과(" + latency + "ms > " + timeout + "ms)") : null;
    }

    @Override
    public PgCancelResult cancel(PgCancelCommand command) {
        pgSide.put(command.paymentKey(), PgPaymentStatus.CANCELED);
        return new PgCancelResult("fake-tx-" + command.idempotencyKey());
    }

    @Override
    public PgQueryResult query(String paymentKey) {
        PgPaymentStatus status = pgSide.getOrDefault(paymentKey, PgPaymentStatus.NOT_FOUND);
        String method = status == PgPaymentStatus.APPROVED ? "CARD" : null;
        return new PgQueryResult(status, method);
    }
}
