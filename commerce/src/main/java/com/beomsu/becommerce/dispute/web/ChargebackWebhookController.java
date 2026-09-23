package com.beomsu.becommerce.dispute.web;

import com.beomsu.becommerce.ledger.internal.LedgerEntry;
import com.beomsu.becommerce.SecurityConfig;
import com.beomsu.becommerce.dispute.internal.DisputeService;
import com.beomsu.becommerce.payment.webhook.WebhookSignatureVerifier;
import com.beomsu.becommerce.shared.DomainException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 차지백 웹훅 수신 REST 컨트롤러.
 *
 * <p>기존 PG 웹훅({@link com.beomsu.becommerce.payment.web.WebhookController})과 <b>같은 인증 패턴</b>을
 * 따른다: payment 모듈의 HMAC 검증기({@link WebhookSignatureVerifier})를 그대로 재사용하고, 서명
 * 위조({@code INVALID_WEBHOOK_SIGNATURE})만 401로, 그 외 처리 예외는 재전송 폭주를 막기 위해 200으로
 * 흡수한다. 개시는 {@code chargebackId}로 멱등해 중복 재전송에도 분쟁이 하나만 생긴다(동기 처리로 충분).
 *
 * <p>{@code /api/v1/webhooks/**}는 {@code SecurityConfig}에서 permitAll(HMAC 자체 인증)이다.
 */
@RestController
@RequestMapping("/api/v1/webhooks")
public class ChargebackWebhookController {

    private static final Logger log = LoggerFactory.getLogger(ChargebackWebhookController.class);

    private final WebhookSignatureVerifier signatureVerifier;
    private final DisputeService disputeService;
    private final ObjectMapper objectMapper;

    public ChargebackWebhookController(WebhookSignatureVerifier signatureVerifier,
                                       DisputeService disputeService,
                                       ObjectMapper objectMapper) {
        this.signatureVerifier = signatureVerifier;
        this.disputeService = disputeService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/chargeback")
    public ResponseEntity<Map<String, Boolean>> receive(
            @RequestBody String rawBody,
            @RequestHeader("X-Signature") String signature) {
        try {
            signatureVerifier.verify(signature, rawBody);
            JsonNode root = objectMapper.readTree(rawBody);
            String chargebackId = text(root, "chargebackId");
            String orderNo = text(root, "orderNo");
            if (chargebackId == null || chargebackId.isBlank() || orderNo == null || orderNo.isBlank()) {
                log.warn("차지백 웹훅 필수 필드 누락(200 반환): chargebackId/orderNo");
                return ResponseEntity.ok(Map.of("received", true));
            }
            // 금액 검증 — 누락/문자열은 asLong()이 0을 주고, 음수도 그대로 온다. 0/음수 분쟁을 개시하면
            // 패소 확정 시 원장 역분개(LedgerEntry 양수 강제)가 깨져 아웃박스 이벤트가 영구 실패한다.
            long amount = root.path("amount").asLong();
            if (amount <= 0) {
                log.warn("차지백 웹훅 금액 비정상(200 반환, 미개시): chargebackId={} amount={}", chargebackId, amount);
                return ResponseEntity.ok(Map.of("received", true));
            }
            disputeService.openFromChargeback(
                    chargebackId, orderNo, longOrNull(root, "paymentId"),
                    amount, text(root, "reason"));
        } catch (DomainException e) {
            // 서명 위조 시도만 401. 그 외 도메인 예외는 200으로 흡수(재전송 폭주 방지).
            if ("INVALID_WEBHOOK_SIGNATURE".equals(e.code())) {
                log.warn("차지백 웹훅 서명 검증 실패: {}", e.getMessage());
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("received", false));
            }
            log.warn("차지백 웹훅 처리 예외(200 반환): {}", e.getMessage());
        } catch (Exception e) {
            // 파싱 실패 등 — 재전송 폭주를 막기 위해 200을 반환하고 로깅만 한다.
            log.warn("차지백 웹훅 처리 예외(200 반환): {}", e.getMessage());
        }
        return ResponseEntity.ok(Map.of("received", true));
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v != null && !v.isNull()) ? v.asText() : null;
    }

    private static Long longOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v != null && !v.isNull()) ? v.asLong() : null;
    }
}
