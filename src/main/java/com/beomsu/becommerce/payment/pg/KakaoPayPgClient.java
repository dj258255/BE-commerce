package com.beomsu.becommerce.payment.pg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;

/**
 * 카카오페이 어댑터 — <b>두 번째 PG</b>.
 *
 * <p><b>어디까지 검증됐나</b>: 실 엔드포인트에 붙어 <b>실패 경로만</b> 확인했다.
 * 온라인결제 API 는 <b>비즈앱(사업자등록)</b> 이 있어야 권한이 열리고, 개인 계정으로 발급한
 * 개발 키로는 {@code -401 권한 없음} 이 돌아온다. 성공 경로는 사업자등록 전까지 확인할 수 없다.
 *
 * <p><b>그럼에도 만든 이유</b>: 그 401 이 <b>진짜 응답</b>이라 쓸모가 있다. 라우팅은
 * "요청이 PG 에 <b>닿지도 못했을 때만</b> 다음 PG 로 넘긴다"는 규칙으로 이중 결제를 막는데,
 * 401 은 <b>닿은 뒤에 거절된 것</b>이라 넘기면 안 되는 경우다. 그 구분을 실 응답으로 고정한다.
 *
 * <p><b>토스 어댑터와 다른 점</b>: 토스는 승인 한 번으로 끝나지만 카카오페이는
 * <b>준비(ready) → 사용자 인증 → 승인(approve)</b> 두 단계다. 우리 {@link PgClient} 계약은
 * 승인 한 번이라, 준비 단계에서 받은 {@code tid} 가 {@code paymentKey} 자리에 온다고 본다.
 */
@Component
@Profile("kakaopay")
public class KakaoPayPgClient implements PgClient {

    private static final Logger log = LoggerFactory.getLogger(KakaoPayPgClient.class);

    /** 카카오페이가 문서로 공개한 테스트 가맹점 코드. 계약 없이도 값 자체는 쓸 수 있다. */
    static final String TEST_CID = "TC0ONETIME";

    /** 사용자 인증 뒤에야 생기는 값이라 지금은 채울 수 없다. {@link #approve} 주석 참고. */
    static final String PG_TOKEN_PLACEHOLDER = "pg-token-not-available-yet";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String cid;

    public KakaoPayPgClient(
            @Value("${payment.kakaopay.base-url:https://open-api.kakaopay.com}") String baseUrl,
            @Value("${payment.kakaopay.secret-key:}") String secretKey,
            @Value("${payment.kakaopay.cid:" + TEST_CID + "}") String cid,
            @Value("${payment.kakaopay.connect-timeout:2s}") Duration connectTimeout,
            @Value("${payment.kakaopay.read-timeout:5s}") Duration readTimeout,
            ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.cid = cid;
        // JDK HttpClient 기반 팩토리를 쓴다.
        //
        // 토스 어댑터가 쓰는 SimpleClientHttpRequestFactory 로 카카오페이에 붙였더니 401 응답의
        // <본문이 비어서> 왔다. curl 로는 {"error_code":-401,...} 가 그대로 오는데도 그랬다.
        // 토스 쪽은 같은 팩토리로 에러 본문이 정상적으로 읽히므로(실 계약 테스트가 에러 코드까지
        // 확인한다) 스프링 일반의 문제로 단정하지 않는다. <이 조합에서 관찰된 사실>만 적는다.
        //
        // 어느 쪽이든 우리에게는 치명적이다 — HTTP 상태가 아니라 <PG 에러 코드>로 판정하기 때문에,
        // 본문을 못 읽으면 판정이 통째로 무너진다. 그래서 본문이 확실히 읽히는 팩토리를 쓴다.
        // 연결 타임아웃은 팩토리가 아니라 HttpClient 에 건다. 팩토리에만 읽기 타임아웃을 걸고
        // 연결 타임아웃을 빠뜨리면 <연결이 안 되는 PG> 에 무한정 매달린다.
        var factory = new org.springframework.http.client.JdkClientHttpRequestFactory(
                java.net.http.HttpClient.newBuilder().connectTimeout(connectTimeout).build());
        factory.setReadTimeout(readTimeout);
        // 카카오페이 인증은 Basic 이 아니라 전용 스킴이다. 개발 키는 DEV_SECRET_KEY 로 보낸다.
        String scheme = secretKey.startsWith("DEV") ? "DEV_SECRET_KEY" : "SECRET_KEY";
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
                .defaultHeader("Authorization", scheme + " " + secretKey)
                .build();
    }

    @Override
    public PgApproveResult approve(PgApproveCommand command) {
        try {
            // 상태 코드로 예외를 던지게 두지 않는다. <에러 본문을 우리가 읽어야> 카카오페이
            // 에러 코드로 판정할 수 있기 때문이다. HTTP 상태만 보면 401 이 다 같은 401 이 된다.
            ResponseEntity<String> res = restClient.post().uri("/online/v1/payment/approve")
                    .header("Content-Type", "application/json")
                    .body(Map.of("cid", cid,
                            "tid", command.paymentKey(),
                            "partner_order_id", command.orderNo(),
                            "partner_user_id", "pay",
                            // pg_token 은 사용자가 결제창에서 인증한 뒤 redirect 로 돌아오는 값이다.
                            // 우리 PgClient 계약(승인 한 번)에는 그 값을 실을 자리가 없다.
                            // <그래서 이 어댑터는 지금 승인을 성공시킬 수 없다.> 권한(비즈앱)이
                            // 열리면 계약을 두 단계로 넓히는 것이 먼저다. 그때까지 이 값은
                            // 자리표시자이고, 그 사실을 숨기지 않는다.
                            "pg_token", PG_TOKEN_PLACEHOLDER))
                    .retrieve()
                    .onStatus(status -> true, (request, response) -> { })
                    .toEntity(String.class);

            if (res.getStatusCode().is2xxSuccessful()) {
                return mapApproved(res.getBody(), command.amount());
            }
            return classify(res.getBody());
        } catch (ResourceAccessException e) {
            // 연결조차 못 맺었으면 요청 바이트가 나가지 않았다 → 다른 PG 로 넘겨도 안전하다는 신호.
            // 읽기 타임아웃처럼 전송 뒤에 끊긴 경우는 승인이 났을 수 있으므로 그대로 던져 미확정으로 남긴다.
            //
            // 이 구분을 <주석으로만> 적어 두고 구현하지 않았었다. 토스 어댑터는 감싸는데 여기는
            // 그냥 던져서, 진짜 연결 실패조차 넘김 대상이 안 됐다. 넘기면 안 되는 쪽으로 틀린
            // 것이라 사고는 안 나지만, 그래서 더 안 찾힌다 — 아무 증상이 없다.
            if (PgUnreachableException.isConnectFailure(e)) {
                throw new PgUnreachableException("카카오페이에 연결하지 못함 order=" + command.orderNo(), e);
            }
            throw e;
        }
    }

    /**
     * <b>HTTP 상태가 아니라 카카오페이 에러 코드로 판정한다.</b> 토스 어댑터와 같은 원칙이다 —
     * 401 이라고 다 같은 401 이 아니다.
     *
     * <p>{@code -401 권한 없음} 은 <b>요청이 닿은 뒤</b> 권한 때문에 거절된 것이다. 재시도해도
     * 같은 답이고, 다른 PG 로 넘길 일도 아니다. 실패로 확정한다.
     */
    private PgApproveResult classify(String raw) {
        int code = 0;
        String message = raw == null ? "" : raw;
        try {
            JsonNode node = objectMapper.readTree(raw);
            code = node.path("error_code").asInt();
            message = node.path("error_message").asText(raw);
        } catch (Exception ignored) {
            // 본문을 못 읽으면 원문을 그대로 사유로 남긴다.
        }
        log.warn("카카오페이 승인 거절 code={} message={}", code, message);
        return PgApproveResult.failed("KAKAOPAY_" + Math.abs(code) + ": " + message);
    }

    /**
     * <b>취소와 조회는 아직 구현하지 않았다.</b> 승인 권한조차 열리지 않은 상태에서 두 경로를
     * 구현하면 <b>실 응답으로 확인할 수 없는 코드가 둘 더</b> 늘 뿐이다. 사업자등록으로 권한이
     * 열리는 시점에 승인부터 확인하고 이어서 만든다. 그때까지는 부르면 명시적으로 실패한다 —
     * 조용히 성공을 흉내내면 그게 더 나쁘다.
     */
    @Override
    public PgCancelResult cancel(PgCancelCommand command) {
        throw new UnsupportedOperationException(
                "카카오페이 취소는 아직 구현하지 않았습니다 — 승인 권한(비즈앱)이 열린 뒤에 만듭니다.");
    }

    @Override
    public PgQueryResult query(String paymentKey) {
        throw new UnsupportedOperationException(
                "카카오페이 조회는 아직 구현하지 않았습니다 — 승인 권한(비즈앱)이 열린 뒤에 만듭니다.");
    }

    /**
     * <b>승인 응답의 금액이 우리가 기대한 금액과 같은지 본다.</b> 토스 어댑터와 같은 규약이다.
     *
     * <p>다르면 성공으로 처리하지 않고 <b>던진다</b>. 승인이 났는지 안 났는지 우리가 모르는
     * 상태이므로 실패로 단정하면 안 되고, 그렇다고 성공으로 넘기면 <b>장부와 실제 돈이
     * 어긋난 채로 확정</b>된다. 던지면 미확정으로 보존되고 조회가 진실을 정한다.
     */
    private PgApproveResult mapApproved(String body, long expectedAmount) {
        try {
            JsonNode node = objectMapper.readTree(body == null ? "{}" : body);
            JsonNode total = node.path("amount").path("total");
            if (!total.isMissingNode() && total.asLong() != expectedAmount) {
                throw new IllegalStateException(
                        "승인 금액 불일치: 요청 " + expectedAmount + ", 응답 " + total.asLong());
            }
            return PgApproveResult.success(
                    node.path("payment_method_type").asText("KAKAOPAY"), "KAKAOPAY");
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            // 본문을 못 읽으면 승인 여부를 모른다. 성공으로 넘기지 않는다.
            throw new IllegalStateException("승인 응답을 읽지 못했습니다: " + e.getMessage(), e);
        }
    }

    private String methodOf(String body) {
        try {
            return objectMapper.readTree(body).path("payment_method_type").asText("KAKAOPAY");
        } catch (Exception e) {
            return "KAKAOPAY";
        }
    }
}
