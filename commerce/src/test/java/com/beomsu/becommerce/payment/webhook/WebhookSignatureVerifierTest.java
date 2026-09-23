package com.beomsu.becommerce.payment.webhook;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebhookSignatureVerifierTest {

    private static final String SECRET = "test-webhook-secret";
    private static final String BODY = "{\"eventId\":\"evt-1\",\"data\":{\"paymentKey\":\"pk-1\"}}";

    /** now 시각에 고정된 Clock으로 검증기를 만든다. */
    private WebhookSignatureVerifier verifierAt(Instant now) {
        return new WebhookSignatureVerifier(SECRET, Clock.fixed(now, ZoneOffset.UTC));
    }

    private String header(long timestamp, String signature) {
        return "t=" + timestamp + ",v1=" + signature;
    }

    @Test
    @DisplayName("유효한 서명·최신 timestamp면 통과한다")
    void validSignaturePasses() {
        Instant now = Instant.now();
        long ts = now.getEpochSecond();
        String sig = WebhookSignatureVerifier.sign(SECRET, ts, BODY);

        assertThat(sig).isNotBlank();
        // 예외가 나지 않으면 통과
        verifierAt(now).verify(header(ts, sig), BODY);
    }

    @Test
    @DisplayName("본문이 변조되면 서명 불일치로 거부한다")
    void tamperedBodyRejected() {
        Instant now = Instant.now();
        long ts = now.getEpochSecond();
        String sig = WebhookSignatureVerifier.sign(SECRET, ts, BODY);
        String tamperedBody = BODY.replace("pk-1", "pk-EVIL");

        assertThatThrownBy(() -> verifierAt(now).verify(header(ts, sig), tamperedBody))
                .isInstanceOf(WebhookException.class)
                .satisfies(e -> assertThat(((WebhookException) e).code()).isEqualTo("INVALID_WEBHOOK_SIGNATURE"));
    }

    @Test
    @DisplayName("잘못된 서명이면 거부한다")
    void wrongSignatureRejected() {
        Instant now = Instant.now();
        long ts = now.getEpochSecond();

        assertThatThrownBy(() -> verifierAt(now).verify(header(ts, "deadbeef00"), BODY))
                .isInstanceOf(WebhookException.class)
                .satisfies(e -> assertThat(((WebhookException) e).code()).isEqualTo("INVALID_WEBHOOK_SIGNATURE"));
    }

    @Test
    @DisplayName("timestamp가 tolerance(5분)를 초과하면 replay로 간주해 거부한다")
    void expiredTimestampRejected() {
        Instant now = Instant.now();
        // 10분 전 timestamp로 유효 서명을 만들어도 tolerance 초과로 거부돼야 한다.
        long oldTs = now.minusSeconds(600).getEpochSecond();
        String sig = WebhookSignatureVerifier.sign(SECRET, oldTs, BODY);

        assertThatThrownBy(() -> verifierAt(now).verify(header(oldTs, sig), BODY))
                .isInstanceOf(WebhookException.class)
                .satisfies(e -> assertThat(((WebhookException) e).code()).isEqualTo("INVALID_WEBHOOK_SIGNATURE"));
    }

    @Test
    @DisplayName("운영 생성자 fail-fast: 시크릿이 비어 있으면 기동을 실패시킨다")
    void publicConstructorRejectsBlankSecret() {
        assertThatThrownBy(() -> new WebhookSignatureVerifier(""))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new WebhookSignatureVerifier((String) null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("운영 생성자 fail-fast: 약한(16바이트 미만) 시크릿이면 기동을 실패시킨다")
    void publicConstructorRejectsWeakSecret() {
        assertThatThrownBy(() -> new WebhookSignatureVerifier("short-key"))  // 9바이트
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("운영 생성자: 16바이트 이상 시크릿이면 정상 생성된다")
    void publicConstructorAcceptsStrongSecret() {
        // 32바이트 로컬 기본값과 같은 결의 충분히 긴 키
        WebhookSignatureVerifier verifier =
                new WebhookSignatureVerifier("local-webhook-secret-please-override-32b");
        assertThat(verifier).isNotNull();
    }
}
