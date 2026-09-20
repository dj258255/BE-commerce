package com.beomsu.becommerce.payment.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/**
 * 토스페이먼츠 웹훅 페이로드를 이 시스템의 정규 봉투로 옮긴다.
 *
 * <p><b>왜 필요한가</b>: {@link WebhookService}는 모든 웹훅에 {@code eventId}(멱등 키)를 요구하는데,
 * 토스는 그 필드를 보내지 않는다. 자체 Mock PG 규약에 맞춰 만든 수신부라 실 토스 웹훅은 파싱 단계에서
 * {@code INVALID_WEBHOOK_PAYLOAD}로 떨어진다 — 실 PG를 붙여 보고 나서야 드러난 간극이다.
 *
 * <p><b>무엇을 하나</b>: 토스가 보낸 필드는 하나도 지우지 않고, {@code eventId}와 (없으면)
 * {@code eventType}만 얹는다. 원본이 그대로 남아야 나중에 무슨 일이 있었는지 되짚을 수 있다.
 *
 * <p><b>멱등 키를 무엇으로 만드나</b>: 토스는 2xx를 못 받으면 같은 이벤트를 다시 보낸다. 그래서
 * 재전송이 같은 값으로 접히도록 <b>내용에서</b> 키를 만든다 — 결제 상태 변경은
 * {@code paymentKey + status}, 입금 콜백은 {@code transactionKey + status}다. 수신 시각처럼
 * 재전송마다 달라지는 값은 쓰지 않는다.
 */
@Component
public class TossWebhookNormalizer {

    /** 토스가 {@code eventType}을 생략하는 입금 콜백에 붙일 유형 이름. */
    static final String DEPOSIT_CALLBACK = "DEPOSIT_CALLBACK";

    private final ObjectMapper objectMapper;

    public TossWebhookNormalizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 토스 페이로드에 {@code eventId}·{@code eventType}을 얹어 정규 봉투로 만든다. */
    public String normalize(String rawBody) {
        JsonNode parsed = parse(rawBody);
        if (!parsed.isObject()) {
            throw new WebhookException("INVALID_WEBHOOK_PAYLOAD", "토스 웹훅 본문이 JSON 객체가 아닙니다");
        }
        ObjectNode root = (ObjectNode) parsed;

        String eventType = text(root, "eventType");
        if (eventType == null) {
            // 입금 콜백은 eventType 없이 transactionKey·secret 만 담겨 온다.
            eventType = DEPOSIT_CALLBACK;
            root.put("eventType", eventType);
        }

        root.put("eventId", buildEventId(root, eventType));
        return write(root);
    }

    /**
     * 재전송이 같은 값으로 접히는 멱등 키를 만든다. 식별자를 못 찾으면 조용히 넘기지 않고 던진다 —
     * 키 없이 저장하면 재전송마다 새 행이 생겨 같은 이벤트를 여러 번 처리한다.
     */
    private String buildEventId(ObjectNode root, String eventType) {
        String status = text(root, "status");
        JsonNode data = root.get("data");

        String paymentKey = text(root, "paymentKey");
        if (paymentKey == null && data != null) {
            paymentKey = text(data, "paymentKey");
            if (status == null) {
                status = text(data, "status");
            }
        }
        if (paymentKey != null) {
            return "toss:" + paymentKey + ":" + (status == null ? eventType : status);
        }

        String transactionKey = text(root, "transactionKey");
        if (transactionKey != null) {
            return "toss:" + transactionKey + ":" + (status == null ? eventType : status);
        }

        throw new WebhookException("INVALID_WEBHOOK_PAYLOAD",
                "토스 웹훅에서 멱등 키로 쓸 식별자(paymentKey·transactionKey)를 찾지 못했습니다");
    }

    private JsonNode parse(String rawBody) {
        try {
            return objectMapper.readTree(rawBody);
        } catch (Exception e) {
            throw new WebhookException("INVALID_WEBHOOK_PAYLOAD", "페이로드 파싱 실패: " + e.getMessage());
        }
    }

    private String write(ObjectNode root) {
        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new WebhookException("INVALID_WEBHOOK_PAYLOAD", "정규화 결과 직렬화 실패: " + e.getMessage());
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v != null && !v.isNull()) ? v.asText() : null;
    }
}
