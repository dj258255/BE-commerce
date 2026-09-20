package com.beomsu.becommerce.payment.pg;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 실 카카오페이 API 계약 검증 — <b>실패 경로만</b> 확인한다.
 *
 * <p><b>왜 성공 경로가 없나</b>: 온라인결제 API 는 <b>비즈앱(사업자등록)</b> 이 있어야 권한이 열린다.
 * 개발자센터에서 애플리케이션을 만들고 개발용 키까지 받았지만, 앱의 `사용 API` 목록에 온라인결제가
 * 없다. 그래서 실제로 부르면 {@code -401 권한 없음} 이 온다. 공개 테스트 CID({@code TC0ONETIME})
 * 가 있어도 <b>그것을 부를 권한 자체</b>가 사업자에게만 열린다.
 *
 * <p><b>그런데 그 401 이 진짜 응답이라 쓸모가 있다.</b> 이 테스트가 고정하는 것은 둘이다.
 * <ol>
 *   <li>어댑터가 그 응답을 <b>실패로 분류</b>하는가 — 예외로 새어 나가지 않는가</li>
 *   <li>라우팅이 그것을 <b>다음 PG 로 넘기지 않는가</b> — 닿은 뒤 거절은 failover 대상이 아니다</li>
 * </ol>
 *
 * <p>키가 없으면 건너뛴다. {@code ./gradlew integrationTest} 로 실행한다.
 */
@Tag("integration")
@DisplayName("카카오페이 실 API — 권한이 없다는 응답까지가 지금 확인할 수 있는 전부다")
class KakaoPayLiveContractTest {

    private static final String KEY_PATH = System.getProperty("user.home") + "/.kakaopay-dev-key";

    private static KakaoPayPgClient client;

    @BeforeAll
    static void loadKey() throws IOException {
        Path p = Path.of(KEY_PATH);
        assumeTrue(Files.exists(p), "카카오페이 개발 키가 없어 건너뜁니다: " + KEY_PATH);
        String key = Files.readString(p).strip();
        client = new KakaoPayPgClient("https://open-api.kakaopay.com", key,
                KakaoPayPgClient.TEST_CID, Duration.ofSeconds(3), Duration.ofSeconds(10),
                new ObjectMapper());
    }

    @Test
    @DisplayName("권한 없는 승인 요청은 <실패>로 분류된다 — 예외로 새지 않는다")
    void permissionDeniedIsClassifiedAsFailure() {
        PgApproveResult result = client.approve(
                new PgApproveCommand("tid-does-not-exist", "ORD-KAKAO-1", 15_000));

        assertThat(result.outcome()).isEqualTo(PgOutcome.FAILED);
        // 사업자등록으로 권한이 열리면 이 코드가 바뀐다. 그때 이 테스트가 먼저 알려준다.
        assertThat(result.failReason()).startsWith("KAKAOPAY_401");
    }

    @Test
    @DisplayName("닿은 뒤 거절은 다음 PG로 넘기지 않는다 — 넘기면 이중 결제 위험이 생긴다")
    void reachedButRejectedDoesNotFailover() {
        // 보조 경로는 부르면 안 된다. 불리면 그게 곧 결함이다.
        PgClient secondary = new PgClient() {
            @Override public PgApproveResult approve(PgApproveCommand c) {
                throw new AssertionError("닿은 뒤 거절인데 보조 PG를 불렀다 — failover 규칙 위반");
            }
            @Override public PgCancelResult cancel(PgCancelCommand c) { throw new UnsupportedOperationException(); }
            @Override public PgQueryResult query(String k) { throw new UnsupportedOperationException(); }
        };
        RoutingPgClient routing = new RoutingPgClient(List.of(
                RoutingPgClient.PgRoute.of("kakaopay", client, 10),
                RoutingPgClient.PgRoute.of("secondary", secondary, 5)));

        PgApproveResult result = routing.approve(
                new PgApproveCommand("tid-does-not-exist", "ORD-KAKAO-2", 15_000));

        assertThat(result.outcome()).isEqualTo(PgOutcome.FAILED);
    }
}
