package com.beomsu.becommerce.payment.webhook;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;

/**
 * 웹훅 HMAC-SHA256 서명 검증기 (Stripe 방식).
 *
 * <p>토스페이먼츠에도 서명이 있지만 {@code payout.changed}·{@code seller.changed} <b>두 이벤트에만</b>
 * 붙는다({@code tosspayments-webhook-signature} 헤더). 우리가 받는 {@code PAYMENT_STATUS_CHANGED}·
 * {@code DEPOSIT_CALLBACK} 에는 없다. 그래서 이 검증기는 자체 Mock PG 기준으로 둔다. 서명 헤더 형식은
 * {@code t=<timestamp>,v1=<hex signature>} 이며 검증 절차는 다음과 같다.
 * <ol>
 *   <li>헤더에서 {@code t}, {@code v1} 파싱</li>
 *   <li>{@code signedPayload = timestamp + "." + rawBody}</li>
 *   <li>secret으로 HMAC-SHA256 계산 → hex</li>
 *   <li>{@link MessageDigest#isEqual} 로 <b>constant-time 비교</b>(타이밍 공격 방지)</li>
 *   <li>timestamp가 tolerance(5분)를 벗어나면 거부(replay 방지)</li>
 * </ol>
 * {@link Clock}을 주입 가능하게 하여 테스트에서 유효/만료 timestamp를 다룰 수 있다.
 */
@Component
public class WebhookSignatureVerifier {

    /** replay 방지를 위한 timestamp 허용 오차. */
    private static final Duration TOLERANCE = Duration.ofMinutes(5);
    private static final String HMAC_ALGO = "HmacSHA256";

    private final String secret;
    private final Clock clock;

    /** 약한 키로 배포되는 사고를 막기 위한 최소 시크릿 길이(바이트). JWT 키 fail-fast와 같은 결. */
    private static final int MIN_SECRET_BYTES = 16;

    /**
     * 운영 주입 생성자 — 시크릿을 fail-fast로 검증한다({@link com.beomsu.becommerce.JwtConfig}와 같은 방식).
     * 기본값을 두지 않으므로 미설정이면 기동을 실패시켜 약한/빈 키로 뜨는 사고를 막는다.
     */
    @Autowired
    public WebhookSignatureVerifier(
            @Value("${payment.webhook.secret}") String secret) {
        this(requireStrongSecret(secret), Clock.systemUTC());
    }

    /** 테스트 전용 — 임의 시크릿·Clock을 주입하기 위한 생성자(검증 없음, timestamp 허용범위 제어). */
    WebhookSignatureVerifier(String secret, Clock clock) {
        this.secret = secret;
        this.clock = clock;
    }

    /** 기동 시 시크릿을 fail-fast로 검증한다 — 미설정/약한 키면 IllegalStateException(약한 키로 배포 차단). */
    private static String requireStrongSecret(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "payment.webhook.secret 미설정 — 웹훅 서명 시크릿을 환경변수/시크릿으로 주입해야 합니다.");
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "payment.webhook.secret 은 최소 " + MIN_SECRET_BYTES
                            + "바이트여야 합니다(약한 키 차단).");
        }
        return secret;
    }

    /**
     * 서명을 검증한다. 실패 시 {@link WebhookException}(code {@code INVALID_WEBHOOK_SIGNATURE})을 던진다.
     */
    public void verify(String signatureHeader, String rawBody) {
        if (signatureHeader == null || signatureHeader.isBlank()) {
            throw invalid("서명 헤더가 없습니다");
        }

        long timestamp = -1;
        String providedSignature = null;
        for (String part : signatureHeader.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            switch (kv[0]) {
                case "t" -> {
                    try {
                        timestamp = Long.parseLong(kv[1].trim());
                    } catch (NumberFormatException e) {
                        throw invalid("timestamp 형식이 올바르지 않습니다");
                    }
                }
                case "v1" -> providedSignature = kv[1].trim();
                default -> { /* 알 수 없는 필드는 무시 */ }
            }
        }

        if (timestamp < 0 || providedSignature == null) {
            throw invalid("서명 헤더 형식이 올바르지 않습니다");
        }

        // constant-time 비교 — 타이밍 공격 방지
        String expected = sign(secret, timestamp, rawBody);
        boolean matches = MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                providedSignature.getBytes(StandardCharsets.UTF_8));
        if (!matches) {
            throw invalid("서명이 일치하지 않습니다");
        }

        // replay 방지 — tolerance(5분) 초과면 거부
        long now = clock.instant().getEpochSecond();
        if (Math.abs(now - timestamp) > TOLERANCE.getSeconds()) {
            throw invalid("서명 timestamp가 허용 범위를 벗어났습니다(replay 의심)");
        }
    }

    /**
     * 유효 서명(hex)을 생성한다. 테스트가 {@code t=<ts>,v1=<sign(...)>} 헤더를 만들 때 사용한다.
     */
    public static String sign(String secret, long timestamp, String rawBody) {
        String signedPayload = timestamp + "." + rawBody;
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
            byte[] raw = mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8));
            return toHex(raw);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 서명 계산 실패", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static WebhookException invalid(String message) {
        return new WebhookException("INVALID_WEBHOOK_SIGNATURE", message);
    }
}
