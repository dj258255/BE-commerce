package com.beomsu.becommerce.payment.web;

import com.beomsu.becommerce.payment.webhook.WebhookException;
import com.beomsu.becommerce.payment.webhook.WebhookService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * PG 웹훅 수신 REST 컨트롤러.
 *
 * <p>PG의 10초 타임아웃·최대 7회 재전송 정책에 대응해 <b>항상 200 {@code {"received": true}}</b>를
 * 반환한다(파싱 실패해도 저장은 됐거나 재처리 경로가 있으므로 5xx로 재전송 폭주를 유발하지 않는다).
 * 예외는 <b>서명 검증 실패({@code INVALID_WEBHOOK_SIGNATURE})만 401</b>로 응답한다.
 */
@RestController
@RequestMapping("/api/v1/webhooks")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final WebhookService webhookService;

    public WebhookController(WebhookService webhookService) {
        this.webhookService = webhookService;
    }

    @PostMapping("/pg")
    public ResponseEntity<Map<String, Boolean>> receive(
            @RequestBody String rawBody,
            @RequestHeader("X-Signature") String signature) {
        try {
            webhookService.handle(signature, rawBody);
        } catch (WebhookException e) {
            // 서명 위조 시도만 401. 그 외 도메인 예외는 저장/재처리 경로가 있으므로 200으로 흡수.
            if ("INVALID_WEBHOOK_SIGNATURE".equals(e.code())) {
                log.warn("웹훅 서명 검증 실패: {}", e.getMessage());
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("received", false));
            }
            log.warn("웹훅 처리 예외(200 반환): {}", e.getMessage());
        } catch (Exception e) {
            // 파싱 실패 등 — 재전송 폭주를 막기 위해 200을 반환하고 로깅만 한다.
            log.warn("웹훅 처리 예외(200 반환): {}", e.getMessage());
        }
        return ResponseEntity.ok(Map.of("received", true));
    }
}
