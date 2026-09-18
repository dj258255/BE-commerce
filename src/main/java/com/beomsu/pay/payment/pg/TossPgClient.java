package com.beomsu.pay.payment.pg;

import com.beomsu.pay.payment.internal.Payment;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * 실 토스페이먼츠 어댑터.
 *
 * <p>{@link PgClient} 인터페이스만 구현하면 되므로, 기존 시스템에 <b>구현 하나만 추가</b>해 실 PG로
 * 갈아끼운다({@code @Qualifier("pgDelegate")} + {@code @Profile("prod")}).
 *
 * <p><b>핵심 원칙</b>: 응답을 못 받았거나 못 알아들었으면 실패로 단정하지 않는다. HTTP 상태 코드가
 * 아니라 <b>토스 에러 코드</b>로 판정한다({@link TossErrorCodes}). 400 {@code PROVIDER_ERROR}는
 * 재시도 대상이고, 400 {@code ALREADY_PROCESSED_PAYMENT}는 이미 승인된 건이라 조회로 확정한다.
 * 둘 다 실패로 처리하면 고객 돈만 나가는 사고가 된다.
 */
@Component
@Qualifier("pgDelegate")
@Profile("prod")
public class TossPgClient implements PgClient {

    private static final Logger log = LoggerFactory.getLogger(TossPgClient.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public TossPgClient(
            @Value("${payment.toss.base-url:https://api.tosspayments.com}") String baseUrl,
            @Value("${payment.toss.secret-key:}") String secretKey,
            @Value("${payment.toss.connect-timeout:2s}") Duration connectTimeout,
            @Value("${payment.toss.read-timeout:5s}") Duration readTimeout,
            ObjectMapper objectMapper) {
        // 토스 인증: 시크릿 키를 아이디로 쓰고 비밀번호는 비운다 — Basic base64(secretKey + ":")
        String basic = Base64.getEncoder()
                .encodeToString((secretKey + ":").getBytes(StandardCharsets.UTF_8));

        // 이 타임아웃이 없으면 느린 PG가 <응답할 때까지> 스레드를 잡는다.
        //
        // 체크아웃은 3단계 사가라 PG 호출이 트랜잭션 밖에 있다(ADR-007). 그래서 이 호출이 늦어져도
        // DB 커넥션은 묶이지 않는다. 다만 <톰캣 워커 스레드>는 여전히 응답까지 묶이고, PG 동시
        // 호출에는 상한이 없다. 즉 느린 PG 앞에서 마르는 자원이 DB 풀에서 스레드 풀로 옮겨간
        // 것이지 사라진 것이 아니다. 어디까지 버틸지는 아직 안 쟀다(ADR-022).
        //
        // Hikari 의 connection-timeout 은 애초에 이걸 못 막는다. 그건 <풀에서 커넥션을 빌리려고
        // 기다리는> 시간 상한이지, 이미 빌린 커넥션을 얼마나 오래 쥐고 있는지와 무관하다.
        //
        // 끊긴 호출은 실패가 아니라 TIMEOUT(=UNKNOWN)으로 흘러 복구 배치가 조회로 확정한다.
        // 그래서 이 값을 줄이면 장애는 빨리 끝나는 대신 미확정이 늘어난다(ADR-022의 선택지 C).
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeader("Authorization", "Basic " + basic)
                .build();
        this.objectMapper = objectMapper;
    }

    @Override
    public PgApproveResult approve(PgApproveCommand command) {
        try {
            TossPayment resp = restClient.post()
                    .uri("/v1/payments/confirm")
                    .header("Idempotency-Key", command.orderNo())   // 주문번호로 PG 멱등 보장
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "paymentKey", command.paymentKey(),
                            "orderId", command.orderNo(),
                            "amount", command.amount()))
                    .retrieve()
                    .body(TossPayment.class);
            return mapConfirm(resp, command.amount());
        } catch (HttpStatusCodeException e) {
            TossError err = parseError(e);
            // 5xx는 코드와 무관하게 미확정이다. 토스 문서상 RETRYABLE인 코드 중 넷은 500번대라,
            // 4xx만 잡으면 분류표에 적어 두고도 실행 경로에 닿지 않는 죽은 매핑이 된다.
            TossErrorCodes.Kind kind = e.getStatusCode().is5xxServerError()
                    ? TossErrorCodes.Kind.RETRYABLE
                    : TossErrorCodes.classify(err.code());
            log.info("토스 승인 실패 응답 order={} status={} code={} kind={}",
                    command.orderNo(), e.getStatusCode(), err.code(), kind);
            return switch (kind) {
                // 확정 실패 — 카드사가 거절했거나 요청이 틀렸다. 승인이 나가지 않았음이 분명하다
                case DECLINED, REQUEST_ERROR, NOT_CANCELABLE -> PgApproveResult.failed(err.describe());
                // 이미 승인된 건 — 실패가 아니다. 추측하지 않고 조회로 확정한다
                case ALREADY_APPROVED, ALREADY_CANCELED -> confirmByQuery(command, err);
                // 일시 오류·미등록 코드 — 승인 여부를 모른다. 던져서 UNKNOWN으로 보존한다
                case RETRYABLE -> throw e;
            };
        } catch (ResourceAccessException e) {
            // 연결조차 못 맺었으면 요청 바이트가 나가지 않았다 → 다른 PG로 넘겨도 안전하다는 신호.
            // 읽기 타임아웃처럼 전송 뒤에 끊긴 경우는 승인이 났을 수 있으므로 그대로 던져 미확정으로 남긴다.
            if (PgUnreachableException.isConnectFailure(e)) {
                throw new PgUnreachableException("토스에 연결하지 못함 order=" + command.orderNo(), e);
            }
            throw e;
        }
    }

    /**
     * "이미 처리된 결제" 응답을 받았을 때 실제 상태를 조회로 확정한다.
     * 조회로도 승인이 확인되지 않으면 원래 예외를 던져 미확정으로 남긴다.
     */
    private PgApproveResult confirmByQuery(PgApproveCommand command, TossError err) {
        PgQueryResult q = query(command.paymentKey());
        if (q.isApproved()) {
            return PgApproveResult.success(q.method());
        }
        if (q.status() == PgPaymentStatus.CANCELED) {
            return PgApproveResult.failed("이미 취소된 결제: " + err.describe());
        }
        return PgApproveResult.timeout("이미 처리됐다고 하나 조회로 확정되지 않음: " + err.describe());
    }

    @Override
    public PgCancelResult cancel(PgCancelCommand command) {
        String paymentKey = command.paymentKey();
        try {
            TossPayment resp = restClient.post()
                    .uri("/v1/payments/{key}/cancel", paymentKey)
                    // 취소 건 단위 멱등키. 같은 취소의 재시도는 같은 키라 두 번 취소되지 않고,
                    // 같은 금액의 서로 다른 부분취소는 순번이 달라 키가 갈린다.
                    .header("Idempotency-Key", command.idempotencyKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("cancelReason", command.reason(),
                            "cancelAmount", command.cancelAmount()))
                    .retrieve()
                    .body(TossPayment.class);
            return new PgCancelResult(transactionKeyOf(resp, paymentKey));
        } catch (HttpStatusCodeException e) {
            TossError err = parseError(e);
            TossErrorCodes.Kind kind = e.getStatusCode().is5xxServerError()
                    ? TossErrorCodes.Kind.RETRYABLE
                    : TossErrorCodes.classify(err.code());
            log.info("토스 취소 실패 응답 paymentKey={} status={} code={} kind={}",
                    paymentKey, e.getStatusCode(), err.code(), kind);
            // 이미 취소돼 있으면 우리가 원하던 상태다 — 보상 재시도가 멱등하게 끝난다
            if (kind == TossErrorCodes.Kind.ALREADY_CANCELED) {
                return new PgCancelResult("toss-already-canceled-" + paymentKey);
            }
            // 다시 보내도 답이 같은 실패는 재시도와 구분한다. 결제가 없거나(NOT_FOUND_PAYMENT)
            // 환불 기간이 지난 건(EXCEED_MAX_REFUND_DUE) 몇 번을 시도해도 결과가 같아서,
            // 재시도 예산을 태우는 대신 곧바로 운영자에게 넘긴다.
            if (kind == TossErrorCodes.Kind.NOT_CANCELABLE || kind == TossErrorCodes.Kind.REQUEST_ERROR) {
                throw new PgCancelNotRetryableException(err.code(), err.message());
            }
            throw e;   // 일시 오류는 위로 던져 보상 재시도로 넘긴다
        }
    }

    @Override
    public PgQueryResult query(String paymentKey) {
        try {
            TossPayment resp = restClient.get()
                    .uri("/v1/payments/{key}", paymentKey)
                    .retrieve()
                    .body(TossPayment.class);
            return new PgQueryResult(mapStatus(resp == null ? null : resp.status()),
                    resp == null ? null : resp.method());
        } catch (HttpClientErrorException.NotFound e) {
            return new PgQueryResult(PgPaymentStatus.NOT_FOUND, null);   // PG에 결제 없음
        }
    }

    // --- 응답 매핑 (HTTP 없이 단위 테스트 가능하도록 static) ---

    /**
     * 승인 응답을 우리 결과로 옮긴다. <b>금액을 반드시 대조한다</b> — 요청 금액과 승인 금액이 다르면
     * 성공으로 처리하지 않고 예외를 던져 미확정으로 남긴다(위변조·연동 오류 방어).
     */
    static PgApproveResult mapConfirm(TossPayment resp, long expectedAmount) {
        if (resp == null) {
            return PgApproveResult.timeout("승인 응답이 비어 있음");
        }
        if (!"DONE".equals(resp.status())) {
            return PgApproveResult.failed("승인되지 않음: " + resp.status());
        }
        if (resp.totalAmount() != null && resp.totalAmount() != expectedAmount) {
            throw new IllegalStateException(
                    "승인 금액 불일치: 요청 " + expectedAmount + ", 응답 " + resp.totalAmount());
        }
        // 같은 카드를 결제 여러 건에 걸쳐 묶을 키. 카드 결제가 아니면 card 가 없어 null 이 된다.
        String fingerprint = resp.card() == null
                ? null
                : CardFingerprint.of(resp.card().number(), resp.card().issuerCode());
        return PgApproveResult.success(resp.method(), null, fingerprint);
    }

    /**
     * 토스 결제 상태를 우리 상태로 옮긴다.
     *
     * <p>{@code READY}·{@code IN_PROGRESS}를 "결제 없음"으로 뭉개면 복구 배치가 진행 중인 결제를
     * 실패로 확정해 버린다. 진행 중은 진행 중으로 남겨 다음 주기에 다시 묻는다.
     */
    static PgPaymentStatus mapStatus(String tossStatus) {
        if (tossStatus == null) {
            return PgPaymentStatus.NOT_FOUND;
        }
        return switch (tossStatus) {
            case "DONE" -> PgPaymentStatus.APPROVED;
            case "CANCELED", "PARTIAL_CANCELED" -> PgPaymentStatus.CANCELED;
            case "READY", "IN_PROGRESS", "WAITING_FOR_DEPOSIT" -> PgPaymentStatus.IN_PROGRESS;
            case "ABORTED", "EXPIRED" -> PgPaymentStatus.NOT_FOUND;   // 승인 실패가 확정된 상태
            default -> PgPaymentStatus.IN_PROGRESS;                   // 모르는 상태는 확정하지 않는다
        };
    }

    static String transactionKeyOf(TossPayment resp, String paymentKey) {
        if (resp != null && resp.cancels() != null && !resp.cancels().isEmpty()) {
            String key = resp.cancels().get(resp.cancels().size() - 1).transactionKey();
            if (key != null) {
                return key;
            }
        }
        return "toss-" + paymentKey;
    }

    TossError parseError(HttpStatusCodeException e) {
        try {
            return objectMapper.readValue(e.getResponseBodyAsString(), TossError.class);
        } catch (Exception ignore) {
            return new TossError(null, e.getStatusText());   // 본문을 못 읽으면 코드 없음 = 미확정
        }
    }

    /** 토스 에러 응답 {@code {"code": "...", "message": "..."}}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record TossError(String code, String message) {
        String describe() {
            return (code == null ? "UNKNOWN" : code) + ": " + (message == null ? "" : message);
        }
    }

    /** 토스 Payment 응답 중 우리가 쓰는 필드. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record TossPayment(String status, String method, Long totalAmount, Long balanceAmount,
                       List<Cancel> cancels, Card card) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        record Cancel(String transactionKey, Long cancelAmount) {
        }

        /**
         * 카드 결제일 때만 온다. <b>여기서 쓰는 것은 마스킹된 번호와 발급사 코드 둘뿐이고,
         * 그대로 저장하지 않는다.</b> {@link CardFingerprint} 가 해시로 바꾼 값만 남는다.
         */
        @JsonIgnoreProperties(ignoreUnknown = true)
        record Card(String number, String issuerCode) {
        }
    }
}
