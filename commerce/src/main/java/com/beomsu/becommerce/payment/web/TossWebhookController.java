package com.beomsu.becommerce.payment.web;

import com.beomsu.becommerce.payment.webhook.TossWebhookNormalizer;
import com.beomsu.becommerce.payment.webhook.WebhookException;
import com.beomsu.becommerce.payment.webhook.WebhookService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 토스페이먼츠 웹훅 수신 컨트롤러.
 *
 * <p><b>왜 {@link WebhookController} 와 따로 두나</b>: 기존 엔드포인트는 {@code X-Signature}
 * 헤더를 <b>필수</b>로 받는다. 그건 자체 Mock PG의 규약이고, 토스는 그 헤더를 보내지 않는다.
 * 같은 경로에서 헤더를 선택으로 바꾸면 <b>Mock PG 쪽 검증까지 우회 가능</b>해진다 — 헤더를 빼고
 * 보내면 검증을 건너뛰게 된다. 그래서 경로를 나눠, 서명을 요구하는 문과 요구하지 않는 문을
 * 섞지 않았다.
 *
 * <p><b>보낸 쪽을 무엇으로 확인하나</b>: 결제 웹훅에는 서명이 없어 요청만 봐서는 확인할 수 없다.
 * (토스의 서명 헤더는 {@code payout.changed}·{@code seller.changed} 에만 붙는다.)
 * 위조를 막는 것은 이 컨트롤러가 아니라 <b>페이로드를 믿지 않는 해석</b>이다 — 상태는 페이로드가
 * 아니라 조회 API가 정한다. 다만 위조 요청이 만드는 <b>쓸모없는 조회와 보류 행</b>은 남으므로,
 * 토스가 공개한 발신 IP로 좁힐 수 있게 해 뒀다({@code payment.webhook.toss-allowed-ips}).
 *
 * <p><b>IP 검사는 기본 off 다</b>: 앱이 프록시·터널 뒤에 있으면 {@code getRemoteAddr()} 이 돌려주는
 * 것은 토스가 아니라 그 앞단의 주소다. 그 상태로 켜면 정상 웹훅까지 막힌다. <b>앞단이 있는
 * 배포에서는 이 검사를 앱이 아니라 그 앞단이 해야 한다.</b> 목록이 비어 있으면 검사하지 않는다.
 *
 * <p><b>응답</b>: 허용 목록에 걸린 요청만 403이고, 나머지는 <b>항상 200</b>이다. 토스는 2xx를 못
 * 받으면 재전송하므로, 우리 쪽 해석 실패를 5xx로 돌려주면 같은 이벤트가 계속 다시 온다.
 */
@RestController
@RequestMapping("/api/v1/webhooks")
public class TossWebhookController {

    private static final Logger log = LoggerFactory.getLogger(TossWebhookController.class);

    private final WebhookService webhookService;
    private final TossWebhookNormalizer normalizer;

    /** 비어 있으면 검사하지 않는다. */
    private final Set<String> allowedIps;

    public TossWebhookController(
            WebhookService webhookService,
            TossWebhookNormalizer normalizer,
            @Value("${payment.webhook.toss-allowed-ips:}") String allowedIpsCsv) {
        this.webhookService = webhookService;
        this.normalizer = normalizer;
        this.allowedIps = Arrays.stream(allowedIpsCsv.split(","))
                .map(String::trim)
                .filter(ip -> !ip.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        if (allowedIps.isEmpty()) {
            log.info("토스 웹훅 발신 IP 검사 off — payment.webhook.toss-allowed-ips 미설정");
        }
    }

    @PostMapping("/toss")
    public ResponseEntity<Map<String, Boolean>> receive(
            @RequestBody String rawBody, HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        if (!allowedIps.isEmpty() && !allowedIps.contains(remoteAddr)) {
            // 여기서만 200을 주지 않는다. 토스가 아닌 곳에서 온 요청이라 재전송을 걱정할 대상이 아니다.
            log.warn("토스 웹훅 허용 목록 밖 발신지 차단 remoteAddr={}", remoteAddr);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("received", false));
        }
        try {
            webhookService.handleUnsigned(normalizer.normalize(rawBody));
        } catch (WebhookException e) {
            log.warn("토스 웹훅 처리 예외(200 반환): code={} {}", e.code(), e.getMessage());
        } catch (Exception e) {
            log.warn("토스 웹훅 처리 예외(200 반환): {}", e.getMessage());
        }
        return ResponseEntity.ok(Map.of("received", true));
    }
}
