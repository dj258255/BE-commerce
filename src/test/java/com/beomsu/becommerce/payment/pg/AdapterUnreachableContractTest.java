package com.beomsu.becommerce.payment.pg;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 어댑터가 <b>넘겨도 되는 실패</b>를 같은 방식으로 표시하는지 고정한다.
 *
 * <p>이 검사가 없어서 한 번 어긋났다. 카카오페이 어댑터는 "이것만 다음 PG 로 넘겨도 되는
 * 경우다"라고 <b>주석에 적어 놓고</b> {@code PgUnreachableException} 으로 감싸지 않아,
 * 진짜 연결 실패조차 넘김 대상이 되지 못했다. 토스 어댑터는 감쌌다. 둘이 달랐다.
 *
 * <p>넘기면 안 되는 쪽으로 틀린 것이라 이중 결제 같은 사고는 안 난다. 그래서 더 위험했다 —
 * <b>아무 증상이 없어서 아무도 안 찾는다.</b> 라우팅은 넘김 여부를 예외 타입 하나로만
 * 판정하므로(`RoutingPgClient`), 그 타입을 던지느냐가 곧 계약이다.
 *
 * <p>어댑터를 실제로 부르지 않고 <b>분류 규칙 자체</b>를 검사한다. 어댑터를 부르려면 PG 키와
 * 네트워크가 필요한데, 여기서 확인하려는 것은 통신이 아니라 "어떤 원인을 넘겨도 되는 것으로
 * 보느냐"라는 판단이다.
 */
class AdapterUnreachableContractTest {

    @Test
    @DisplayName("연결조차 못 맺은 경우만 넘겨도 되는 실패로 본다")
    void onlyConnectFailureIsFailoverable() {
        assertThat(PgUnreachableException.isConnectFailure(
                new ResourceAccessException("연결 거부", new ConnectException("Connection refused"))))
                .as("연결 거부 — 요청 바이트가 안 나갔다")
                .isTrue();

        assertThat(PgUnreachableException.isConnectFailure(
                new ResourceAccessException("호스트 못 찾음", new java.net.UnknownHostException("pg.invalid"))))
                .as("호스트 해석 실패 — 요청 바이트가 안 나갔다")
                .isTrue();
    }

    @Test
    @DisplayName("전송 뒤에 끊긴 경우는 넘기지 않는다 — 승인이 났을 수 있다")
    void readTimeoutIsNotFailoverable() {
        assertThat(PgUnreachableException.isConnectFailure(
                new ResourceAccessException("읽기 타임아웃", new SocketTimeoutException("Read timed out"))))
                .as("읽기 타임아웃 — 요청은 나갔고 승인됐을 수 있다")
                .isFalse();

        assertThat(PgUnreachableException.isConnectFailure(
                new ResourceAccessException("전송 중 끊김", new IOException("Broken pipe"))))
                .as("전송 뒤 끊김 — 승인 여부를 모른다")
                .isFalse();
    }

    /**
     * 어댑터가 늘면 여기서 걸린다. 넘김 판정은 예외 타입 하나로만 하므로, 새 어댑터가
     * 그 타입을 안 던지면 <b>영원히 넘어가지 않는데 아무도 모른다.</b>
     */
    @Test
    @DisplayName("모든 PG 어댑터가 연결 실패를 PgUnreachableException 으로 감싼다")
    void everyAdapterWrapsConnectFailure() throws Exception {
        List<Class<?>> adapters = List.of(TossPgClient.class, KakaoPayPgClient.class);
        for (Class<?> adapter : adapters) {
            String src = java.nio.file.Files.readString(java.nio.file.Path.of(
                    "src/main/java/" + adapter.getName().replace('.', '/') + ".java"));
            assertThat(src)
                    .as("%s 가 연결 실패를 넘김 신호로 감싸지 않는다 — 라우팅이 이 어댑터를 영영 못 넘긴다",
                            adapter.getSimpleName())
                    .contains("PgUnreachableException.isConnectFailure")
                    .contains("throw new PgUnreachableException");
        }
    }
}
