package com.beomsu.becommerce.shorts;

import com.beomsu.becommerce.testsupport.SharedContainers;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 숏폼 업로드 API 통합 테스트(R21) — 판매자 전용 인가, 메타 검증, presigned 업로드→완료 흐름을
 * 실제 서버·실 MySQL로 끝까지 검증한다.
 *
 * <p>{@code @Tag("integration")}이라 기본 스위트에서 제외된다. CI는 {@code ./gradlew integrationTest}로 돌린다.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("숏폼 업로드 API 통합(R21) — 판매자 인가, 메타 검증, 업로드 완료 흐름")
class ShortsApiIntegrationTest {

    @DynamicPropertySource
    static void datasourceAndRedis(DynamicPropertyRegistry registry) {
        SharedContainers.register(registry, "Shorts");
    }

    @Autowired
    TestRestTemplate rest;

    private static final Map<String, String> TOKENS = new ConcurrentHashMap<>();

    @Test
    @DisplayName("R21: 판매자가 업로드 시작→(저장소에 업로드)→완료 알림→조회까지 성공한다")
    void happyPathStartCompleteGet() throws IOException {
        String seller = authToken("3", "seller-local-only");

        ResponseEntity<JsonNode> started = start(seller, validRequest());
        assertThat(started.getStatusCode().is2xxSuccessful()).isTrue();
        long id = started.getBody().get("shortVideoId").asLong();
        String uploadUrl = started.getBody().get("uploadUrl").asText();
        assertThat(uploadUrl).startsWith("file://");

        // presigned URL로의 실제 업로드를 흉내낸다 — 저장소(로컬 디스크)에 바이트를 직접 둔다.
        putFakeFileAt(uploadUrl);

        ResponseEntity<JsonNode> completed = complete(seller, id);
        assertThat(completed.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(completed.getBody().get("status").asText()).isEqualTo("UPLOADED");

        ResponseEntity<JsonNode> fetched = get(seller, id);
        assertThat(fetched.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(fetched.getBody().get("status").asText()).isEqualTo("UPLOADED");
        assertThat(fetched.getBody().get("durationSeconds").asInt()).isEqualTo(30);
    }

    @Test
    @DisplayName("R21 경계: 61초 영상은 업로드 시작에서 400 INVALID_DURATION으로 거절된다")
    void tooLongDurationIsRejected() {
        String seller = authToken("3", "seller-local-only");

        ResponseEntity<JsonNode> res = start(seller,
                Map.of("durationSeconds", 61, "fileSizeBytes", 1_000, "width", 1080, "height", 1920,
                        "contentType", "video/mp4"));

        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(res.getBody().get("code").asText()).isEqualTo("INVALID_DURATION");
    }

    @Test
    @DisplayName("R21 경계: 가로 비율 영상은 업로드 시작에서 400 INVALID_ASPECT_RATIO로 거절된다")
    void nonPortraitAspectRatioIsRejected() {
        String seller = authToken("3", "seller-local-only");

        ResponseEntity<JsonNode> res = start(seller,
                Map.of("durationSeconds", 10, "fileSizeBytes", 1_000, "width", 1920, "height", 1080,
                        "contentType", "video/mp4"));

        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(res.getBody().get("code").asText()).isEqualTo("INVALID_ASPECT_RATIO");
    }

    @Test
    @DisplayName("R21: 실제로 업로드하지 않고 완료를 알리면 UPLOAD_NOT_FOUND로 거절된다")
    void completingWithoutActualUploadIsRejected() {
        String seller = authToken("3", "seller-local-only");
        long id = start(seller, validRequest()).getBody().get("shortVideoId").asLong();

        ResponseEntity<JsonNode> res = complete(seller, id);

        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(res.getBody().get("code").asText()).isEqualTo("UPLOAD_NOT_FOUND");
    }

    @Test
    @DisplayName("R21: 일반 사용자(ROLE_USER)는 숏폼 업로드를 시작할 수 없다 — 403")
    void regularUserCannotUpload() {
        String user = authToken("1", "user-local-only");

        ResponseEntity<JsonNode> res = start(user, validRequest());

        assertThat(res.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    @DisplayName("R21: 비로그인은 401 — 숏폼 업로드는 인증이 필요하다")
    void anonymousIsRejected() {
        ResponseEntity<JsonNode> res = rest.postForEntity("/api/v1/shorts",
                new HttpEntity<>(validRequest(), json()), JsonNode.class);

        assertThat(res.getStatusCode().value()).isEqualTo(401);
    }

    // --- 헬퍼 ---

    private Map<String, Object> validRequest() {
        return Map.of("durationSeconds", 30, "fileSizeBytes", 10_000_000, "width", 1080, "height", 1920,
                "contentType", "video/mp4");
    }

    private void putFakeFileAt(String uploadUrl) throws IOException {
        Path path = Path.of(uploadUrl.substring("file://".length()));
        Files.createDirectories(path.getParent());
        Files.write(path, new byte[] {1, 2, 3});
    }

    private ResponseEntity<JsonNode> start(String token, Map<String, ?> body) {
        return rest.exchange("/api/v1/shorts", HttpMethod.POST, new HttpEntity<>(body, bearer(token)),
                JsonNode.class);
    }

    private ResponseEntity<JsonNode> complete(String token, long id) {
        return rest.exchange("/api/v1/shorts/" + id + "/complete", HttpMethod.POST,
                new HttpEntity<>(null, bearer(token)), JsonNode.class);
    }

    private ResponseEntity<JsonNode> get(String token, long id) {
        return rest.exchange("/api/v1/shorts/" + id, HttpMethod.GET,
                new HttpEntity<>(null, bearer(token)), JsonNode.class);
    }

    /** 사용자별 토큰을 한 번만 받는다 — 로그인은 IP 기준 초당 한도(5/s)에 걸린다(RateLimitFilter). */
    private String authToken(String username, String password) {
        return TOKENS.computeIfAbsent(username, u -> login(u, password));
    }

    private String login(String username, String password) {
        ResponseEntity<JsonNode> res = rest.postForEntity("/api/v1/auth/login",
                new HttpEntity<>(Map.of("username", username, "password", password), json()), JsonNode.class);
        assertThat(res.getStatusCode().is2xxSuccessful())
                .describedAs("로그인 실패 — status=%s body=%s", res.getStatusCode(), res.getBody())
                .isTrue();
        return res.getBody().get("token").asText();
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders h = json();
        h.setBearerAuth(token);
        return h;
    }

    private HttpHeaders json() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }
}
