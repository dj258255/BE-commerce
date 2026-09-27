package com.beomsu.becommerce.payment.pg;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 승인 요청의 {@code Idempotency-Key} 헤더가 {@link PgApproveCommand#idempotencyKey()} 를 그대로
 * 실어 나르는지 확인한다(#402). 주문번호를 그대로 쓰면 거절 뒤 같은 주문으로 재시도했을 때 토스가
 * 이전 응답을 그대로 돌려줄 위험이 있어, 시도(paymentId)별 값이어야 한다.
 */
class TossPgClientIdempotencyKeyTest {

    private static final String APPROVED_BODY =
            "{\"status\":\"DONE\",\"method\":\"카드\",\"totalAmount\":10000,\"balanceAmount\":10000}";

    private TossPgClient client(MockRestServiceServer[] serverOut) {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.tosspayments.com");
        serverOut[0] = MockRestServiceServer.bindTo(builder).build();
        return new TossPgClient(builder.build(), new ObjectMapper());
    }

    @Test
    @DisplayName("Idempotency-Key 헤더는 command.idempotencyKey() 를 그대로 쓴다 — 주문번호가 아니다")
    void headerCarriesPerAttemptKey() {
        MockRestServiceServer[] serverBox = new MockRestServiceServer[1];
        TossPgClient toss = client(serverBox);
        MockRestServiceServer server = serverBox[0];

        server.expect(requestTo("https://api.tosspayments.com/v1/payments/confirm"))
                .andExpect(header("Idempotency-Key", "order-1:42"))
                .andRespond(withSuccess(APPROVED_BODY, MediaType.APPLICATION_JSON));

        PgApproveResult result = toss.approve(
                new PgApproveCommand("pk-1", "order-1", 10_000, 0, "order-1:42"));

        server.verify();
        assertOutcomeSuccess(result);
    }

    @Test
    @DisplayName("같은 주문번호라도 시도(paymentId)가 다르면 헤더 값이 달라진다")
    void differentAttemptMeansDifferentHeaderValue() {
        MockRestServiceServer[] serverBox = new MockRestServiceServer[1];
        TossPgClient toss = client(serverBox);
        MockRestServiceServer server = serverBox[0];

        server.expect(requestTo("https://api.tosspayments.com/v1/payments/confirm"))
                .andExpect(header("Idempotency-Key", "order-1:1"))
                .andRespond(withSuccess(APPROVED_BODY, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.tosspayments.com/v1/payments/confirm"))
                .andExpect(header("Idempotency-Key", "order-1:2"))
                .andRespond(withSuccess(APPROVED_BODY, MediaType.APPLICATION_JSON));

        toss.approve(new PgApproveCommand("pk-1", "order-1", 10_000, 0, "order-1:1"));
        toss.approve(new PgApproveCommand("pk-2", "order-1", 10_000, 0, "order-1:2"));

        server.verify(); // 두 기대 요청 모두 각자 선언된 헤더 값으로 실제 매칭됐어야 통과한다
    }

    private void assertOutcomeSuccess(PgApproveResult result) {
        org.assertj.core.api.Assertions.assertThat(result.outcome()).isEqualTo(PgOutcome.SUCCESS);
    }
}
