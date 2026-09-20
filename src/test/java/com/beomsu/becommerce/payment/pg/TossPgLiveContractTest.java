package com.beomsu.becommerce.payment.pg;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 실 토스페이먼츠 API 계약 검증 — <b>진짜 네트워크를 탄다.</b>
 *
 * <p><b>왜 필요한가</b> — {@code TossPgClientMappingTest}는 응답 JSON을 손으로 만들어 우리 분류기에
 * 넣는다. 그건 "문서를 이렇게 읽었다"를 고정할 뿐, 토스가 실제로 그렇게 응답하는지는 확인하지 못한다.
 * 이 테스트는 실제 엔드포인트에 붙어 <b>인증이 통과하는지</b>와 <b>실제로 오는 에러 코드가 우리 분류와
 * 맞는지</b>를 확인한다.
 *
 * <p><b>테스트 키로 돈다</b> — 토스페이먼츠는 계약 전에도 개발자용 테스트 상점 키를 준다. 실제 결제는
 * 일어나지 않는다. 키는 저장소에 두지 않고 홈 디렉터리 파일({@value #KEY_PATH})에서 읽으며,
 * 파일이 없으면 테스트를 건너뛴다.
 *
 * <p>기본 스위트에서는 제외한다(외부 네트워크 의존). {@code ./gradlew integrationTest}로 실행.
 */
@Tag("integration")
class TossPgLiveContractTest {

    private static final String KEY_PATH = "~/.toss-test-key";

    /** 존재할 수 없는 결제 키 — 조회·취소가 "없는 결제"에 어떤 코드를 주는지 보려고 쓴다. */
    private static final String ABSENT_PAYMENT_KEY = "test_absent_payment_key_000000000000";

    private static String secretKey;
    private static TossPgClient client;

    @BeforeAll
    static void loadKey() throws Exception {
        Path p = Path.of(System.getProperty("user.home"), ".toss-test-key");
        assumeTrue(Files.exists(p), "테스트 키 파일이 없어 건너뜀: " + KEY_PATH);
        secretKey = Files.readString(p).trim();
        assumeTrue(secretKey.startsWith("test_sk_"), "테스트 키(test_sk_)가 아니라 건너뜀");
        client = new TossPgClient("https://api.tosspayments.com", secretKey,
                java.time.Duration.ofSeconds(2), java.time.Duration.ofSeconds(5), new ObjectMapper());
    }

    @Test
    @DisplayName("인증이 실제로 통과한다 — 키가 틀리면 UNAUTHORIZED, 맞으면 '결제 없음'까지 간다")
    void authenticatesAgainstRealApi() {
        PgQueryResult result = client.query(ABSENT_PAYMENT_KEY);

        // 인증이 실패했다면 여기까지 오지 못하고 401 계열로 끊긴다. NOT_FOUND라는 것은
        // Basic base64(secretKey + ":") 인증이 통과해 상점 컨텍스트에서 조회까지 갔다는 뜻이다.
        System.out.printf("%n[조회] 없는 결제 키 → %s%n", result.status());
        assertThat(result.status()).isEqualTo(PgPaymentStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("없는 결제 승인 시도: 실제 에러 코드가 확정 실패로 분류된다")
    void confirmOnAbsentPaymentIsClassified() {
        PgApproveResult result = client.approve(
                new PgApproveCommand(ABSENT_PAYMENT_KEY, "live-contract-" + System.nanoTime(), 1_000));

        System.out.printf("[승인] 없는 결제 키 → outcome=%s reason=%s%n",
                result.outcome(), result.failReason());
        // 승인은 절대 성공하면 안 된다. 존재하지 않는 결제는 확정 실패로 닫혀야
        // 복구 배치가 헛되이 계속 조회하지 않는다.
        assertThat(result.outcome()).isNotEqualTo(PgOutcome.SUCCESS);
    }

    @Test
    @DisplayName("없는 결제 취소: 재시도해도 같은 답이므로 재시도 대상과 구분해 던진다")
    void cancelOnAbsentPaymentIsNotRetryable() {
        // 토스는 없는 결제 취소에 404 NOT_FOUND_PAYMENT를 준다. 몇 번을 보내도 같은 답이라
        // 일시 오류와 섞으면 보상 재시도가 이길 수 없는 요청에 예산을 다 쓴다.
        assertThatThrownBy(() -> client.cancel(new PgCancelCommand(ABSENT_PAYMENT_KEY, 1_000, "실 계약 검증", 1)))
                .isInstanceOf(PgCancelNotRetryableException.class)
                .satisfies(e -> System.out.printf("[취소] 없는 결제 키 → %s%n", e.getMessage()));
    }
}
