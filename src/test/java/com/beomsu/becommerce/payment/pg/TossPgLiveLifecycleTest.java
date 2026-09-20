package com.beomsu.becommerce.payment.pg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 실 토스페이먼츠에서 <b>결제 한 건의 전 과정</b>을 태운다 — 승인 → 조회 → 취소 → 재취소.
 *
 * <p><b>왜 빌링(자동결제)으로 하는가</b> — 일반 결제의 {@code paymentKey}는 사용자가 결제창에서
 * 인증을 마쳐야 발급되므로 서버만으로는 만들 수 없다. 자동결제는 카드 정보를 서버가 직접 보내
 * 빌링키를 받고 그 키로 승인하므로, 브라우저 없이도 <b>실제로 승인된 결제</b>를 만들 수 있다.
 * 테스트 상점에서는 카드번호 앞 여섯 자리(BIN)만 유효하면 등록된다.
 *
 * <p><b>무엇을 검증하는가</b> — 빌링 발급·승인은 이 테스트가 직접 호출해 <b>진짜 결제를 만드는
 * 준비 단계</b>이고, 검증 대상은 그 결제를 우리 어댑터({@link TossPgClient})가 제대로 다루는지다.
 * 손으로 만든 응답이 아니라 토스가 실제로 준 응답으로 조회 매핑과 취소 멱등을 확인한다.
 *
 * <p>키는 홈 디렉터리 파일에서 읽고 없으면 건너뛴다. {@code ./gradlew integrationTest}로 실행.
 */
@Tag("integration")
class TossPgLiveLifecycleTest {

    private static final long AMOUNT = 10_000L;

    /** 카드 정보는 저장소에 두지 않는다. 홈 디렉터리 파일에서 한 줄씩 읽고, 없으면 테스트를 건너뛴다. */
    private static String cardNumber, expYear, expMonth, birth, cardPassword;

    private static RestClient toss;
    private static ObjectMapper mapper;
    private static TossPgClient client;

    @BeforeAll
    static void loadKey() throws Exception {
        Path p = Path.of(System.getProperty("user.home"), ".toss-test-key");
        assumeTrue(Files.exists(p), "테스트 키 파일이 없어 건너뜀");
        String secretKey = Files.readString(p).trim();
        assumeTrue(secretKey.startsWith("test_sk_"), "테스트 키가 아니라 건너뜀");

        String basic = Base64.getEncoder()
                .encodeToString((secretKey + ":").getBytes(StandardCharsets.UTF_8));
        toss = RestClient.builder()
                .baseUrl("https://api.tosspayments.com")
                .defaultHeader("Authorization", "Basic " + basic)
                .build();
        mapper = new ObjectMapper();
        client = new TossPgClient("https://api.tosspayments.com", secretKey,
                java.time.Duration.ofSeconds(2), java.time.Duration.ofSeconds(5), mapper);

        // 토스는 테스트용 국내 카드번호를 제공하지 않는다(테스트 환경에서도 실제 카드 유형이어야
        // 승인이 난다. 단 실제 출금은 일어나지 않는다). 그래서 카드 정보는 커밋하지 않고
        // ~/.toss-test-card 에서 읽는다. 파일이 없으면 이 테스트는 건너뛴다.
        Path cardFile = Path.of(System.getProperty("user.home"), ".toss-test-card");
        assumeTrue(Files.exists(cardFile),
                "카드 정보 파일(~/.toss-test-card)이 없어 건너뜀 — 카드번호/YY/MM/생년월일6/비밀번호2 를 줄바꿈으로");
        String[] lines = Files.readString(cardFile).trim().split("\\R");
        assumeTrue(lines.length >= 5, "카드 정보가 5줄이 아니라 건너뜀");
        cardNumber = lines[0].trim();
        expYear = lines[1].trim();
        expMonth = lines[2].trim();
        birth = lines[3].trim();
        cardPassword = lines[4].trim();
    }

    @Test
    @DisplayName("실제 결제를 만들어 승인 → 조회 → 취소 → 재취소까지 우리 어댑터로 확인한다")
    void fullLifecycleAgainstRealApi() {
        String customerKey = "cust-" + UUID.randomUUID();
        String orderNo = "live-" + System.currentTimeMillis();

        // --- 준비 1. 빌링키 발급(카드 등록) ---
        JsonNode billing = toss.post()
                .uri("/v1/billing/authorizations/card")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "customerKey", customerKey,
                        "cardNumber", cardNumber,
                        "cardExpirationYear", expYear,
                        "cardExpirationMonth", expMonth,
                        "customerIdentityNumber", birth,
                        "cardPassword", cardPassword))
                .retrieve()
                .body(JsonNode.class);
        String billingKey = billing.path("billingKey").asText();
        System.out.printf("%n[빌링키 발급] %s%n", billingKey.substring(0, Math.min(16, billingKey.length())) + "…");
        assertThat(billingKey).isNotBlank();

        // --- 준비 2. 그 빌링키로 실제 승인 → 진짜 paymentKey가 생긴다 ---
        JsonNode approved = toss.post()
                .uri("/v1/billing/{billingKey}", billingKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "customerKey", customerKey,
                        "amount", AMOUNT,
                        "orderId", orderNo,
                        "orderName", "실 계약 검증 결제"))
                .retrieve()
                .body(JsonNode.class);
        String paymentKey = approved.path("paymentKey").asText();
        System.out.printf("[승인] status=%s amount=%s method=%s%n",
                approved.path("status").asText(), approved.path("totalAmount").asLong(),
                approved.path("method").asText());
        assertThat(approved.path("status").asText()).isEqualTo("DONE");

        // --- 검증 1. 우리 어댑터의 조회 매핑이 실제 응답에 맞는가 ---
        PgQueryResult queried = client.query(paymentKey);
        System.out.printf("[조회] 우리 매핑 → %s (수단 %s)%n", queried.status(), queried.method());
        assertThat(queried.status()).isEqualTo(PgPaymentStatus.APPROVED);

        // --- 검증 2. 실제 취소가 되는가 ---
        PgCancelResult canceled = client.cancel(new PgCancelCommand(paymentKey, AMOUNT, "실 계약 검증 취소", 1));
        System.out.printf("[취소] transactionKey=%s%n", canceled.transactionKey());
        assertThat(canceled.transactionKey()).isNotBlank();

        // --- 검증 3. 취소 뒤 조회가 CANCELED로 매핑되는가 ---
        PgQueryResult afterCancel = client.query(paymentKey);
        System.out.printf("[취소 후 조회] 우리 매핑 → %s%n", afterCancel.status());
        assertThat(afterCancel.status()).isEqualTo(PgPaymentStatus.CANCELED);

        // --- 검증 4. 같은 취소를 다시 보내도 멱등하게 끝나는가 ---
        // 보상 재시도가 같은 요청을 다시 보내는 상황이다. 예외로 끊기면 재시도가 영영 안 끝난다.
        PgCancelResult again = client.cancel(new PgCancelCommand(paymentKey, AMOUNT, "실 계약 검증 취소", 1));
        System.out.printf("[재취소] 멱등 처리 → %s%n", again.transactionKey());
        assertThat(again.transactionKey()).isNotBlank();
    }
}
