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
 * 숏폼 업로드·상품 연결·피드 API 통합 테스트(R21·R25·R26) — 판매자 전용 인가, 메타 검증, presigned
 * 업로드→완료 흐름, 상품 연결·해제, 공개 피드 조회를 실제 서버·실 MySQL로 끝까지 검증한다.
 *
 * <p>{@code @Tag("integration")}이라 기본 스위트에서 제외된다. CI는 {@code ./gradlew integrationTest}로 돌린다.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("숏폼 업로드·상품 연결·피드 API 통합(R21·R25·R26) — 판매자 인가, 메타 검증, 업로드·상품 연결·피드 흐름")
class ShortsApiIntegrationTest {

    @DynamicPropertySource
    static void datasourceAndRedis(DynamicPropertyRegistry registry) {
        SharedContainers.register(registry, "Shorts");
    }

    @Autowired
    TestRestTemplate rest;

    /**
     * 변환 워커가 아직 없어(다음 단계) READY로 가는 API가 없다 — 테스트에서만 저장소를 직접 조작해
     * READY를 만든다(UPLOADING→UPLOADED→PROBING→TRANSCODING→READY, {@link ShortVideo}의 상태
     * 전이 규칙을 그대로 따라간다).
     */
    @Autowired
    ShortVideoRepository videoRepository;

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

    @Test
    @DisplayName("R25: 판매자가 상품을 연결하면 조회 응답에 id·이름·가격이 담긴다")
    void linkingProductAppearsInGetResponse() {
        String seller = authToken("3", "seller-local-only");
        long id = start(seller, validRequest()).getBody().get("shortVideoId").asLong();

        ResponseEntity<JsonNode> linked = linkProduct(seller, id, 1L);
        assertThat(linked.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode products = linked.getBody().get("products");
        assertThat(products).hasSize(1);
        assertThat(products.get(0).get("productId").asLong()).isEqualTo(1L);
        assertThat(products.get(0).get("name").asText()).isEqualTo("테스트 상품 A");
        assertThat(products.get(0).get("price").asLong()).isEqualTo(10000L);

        ResponseEntity<JsonNode> fetched = get(seller, id);
        assertThat(fetched.getBody().get("products")).hasSize(1);
    }

    @Test
    @DisplayName("R25: 없는 상품을 연결하면 404 PRODUCT_NOT_FOUND로 거절되고 연결되지 않는다")
    void linkingNonexistentProductIsRejected() {
        String seller = authToken("3", "seller-local-only");
        long id = start(seller, validRequest()).getBody().get("shortVideoId").asLong();

        ResponseEntity<JsonNode> res = linkProduct(seller, id, 999_999L);

        assertThat(res.getStatusCode().value()).isEqualTo(404);
        assertThat(res.getBody().get("code").asText()).isEqualTo("PRODUCT_NOT_FOUND");
        assertThat(get(seller, id).getBody().get("products")).isEmpty();
    }

    @Test
    @DisplayName("R25: 상품 연결을 해제하면 조회 응답에서 사라진다")
    void unlinkingProductRemovesItFromResponse() {
        String seller = authToken("3", "seller-local-only");
        long id = start(seller, validRequest()).getBody().get("shortVideoId").asLong();
        linkProduct(seller, id, 1L);
        linkProduct(seller, id, 2L);

        ResponseEntity<JsonNode> unlinked = unlinkProduct(seller, id, 1L);

        assertThat(unlinked.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode products = unlinked.getBody().get("products");
        assertThat(products).hasSize(1);
        assertThat(products.get(0).get("productId").asLong()).isEqualTo(2L);
    }

    @Test
    @DisplayName("R26: 비로그인도 피드를 볼 수 있고, READY가 아닌 숏폼은 나오지 않는다")
    void anonymousCanSeeFeedWithOnlyReadyVideos() {
        String seller = authToken("3", "seller-local-only");
        long readyId = start(seller, validRequest()).getBody().get("shortVideoId").asLong();
        long uploadingId = start(seller, validRequest()).getBody().get("shortVideoId").asLong();
        markReady(readyId);
        // uploadingId는 그대로 UPLOADING — complete()도 부르지 않았다.

        ResponseEntity<JsonNode> res = feed(null, null); // 토큰 없이 호출 — 비로그인 시청(R5와 같은 원칙)

        assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode ids = res.getBody().get("items");
        boolean containsReady = false;
        for (JsonNode item : ids) {
            long itemId = item.get("id").asLong();
            assertThat(itemId).isNotEqualTo(uploadingId); // READY가 아닌 숏폼은 절대 나오지 않는다
            if (itemId == readyId) {
                containsReady = true;
            }
        }
        assertThat(containsReady).isTrue();
    }

    @Test
    @DisplayName("R26: 피드 항목에는 연결 상품 요약(id·이름·가격)이 담긴다")
    void feedItemIncludesLinkedProductSummary() {
        String seller = authToken("3", "seller-local-only");
        long id = start(seller, validRequest()).getBody().get("shortVideoId").asLong();
        linkProduct(seller, id, 2L);
        markReady(id);

        JsonNode item = findFeedItem(feed(null, null), id);

        assertThat(item).isNotNull();
        JsonNode products = item.get("products");
        assertThat(products).hasSize(1);
        assertThat(products.get(0).get("productId").asLong()).isEqualTo(2L);
        assertThat(products.get(0).get("name").asText()).isEqualTo("테스트 상품 B");
        assertThat(products.get(0).get("price").asLong()).isEqualTo(5000L);
    }

    @Test
    @DisplayName("R26 경계: 커서로 다음 쪽을 받으면 이전 쪽 항목과 겹치지 않는다")
    void cursorPaginationMovesToNextDistinctPage() {
        String seller = authToken("3", "seller-local-only");
        long id1 = start(seller, validRequest()).getBody().get("shortVideoId").asLong();
        long id2 = start(seller, validRequest()).getBody().get("shortVideoId").asLong();
        long id3 = start(seller, validRequest()).getBody().get("shortVideoId").asLong();
        markReady(id1);
        markReady(id2);
        markReady(id3);

        ResponseEntity<JsonNode> firstPage = feed(null, 2);
        JsonNode firstItems = firstPage.getBody().get("items");
        assertThat(firstItems).hasSize(2);
        // id3가 가장 나중에 READY된(가장 최신) 것이므로 첫 쪽 맨 앞이어야 한다.
        assertThat(firstItems.get(0).get("id").asLong()).isEqualTo(id3);
        assertThat(firstItems.get(1).get("id").asLong()).isEqualTo(id2);
        assertThat(firstPage.getBody().get("hasNext").asBoolean()).isTrue();
        long nextCursor = firstPage.getBody().get("nextCursor").asLong();
        assertThat(nextCursor).isEqualTo(id2);

        ResponseEntity<JsonNode> secondPage = feed(nextCursor, 2);
        JsonNode secondItems = secondPage.getBody().get("items");
        assertThat(secondItems.get(0).get("id").asLong()).isEqualTo(id1);
        for (JsonNode item : secondItems) {
            long itemId = item.get("id").asLong();
            assertThat(itemId).isNotIn(id2, id3); // 이전 쪽에서 이미 본 항목과 겹치지 않는다
        }
    }

    // --- 헬퍼 ---

    /** 변환 워커 없이 테스트에서 직접 READY로 만든다({@link ShortVideo}의 상태 전이를 그대로 탄다). */
    private void markReady(long id) {
        ShortVideo v = videoRepository.findById(id).orElseThrow();
        v.markUploaded();
        v.startProbing();
        v.startTranscoding();
        v.markReady();
        videoRepository.save(v);
    }

    private ResponseEntity<JsonNode> feed(Long cursor, Integer size) {
        StringBuilder path = new StringBuilder("/api/v1/shorts/feed");
        String sep = "?";
        if (cursor != null) {
            path.append(sep).append("cursor=").append(cursor);
            sep = "&";
        }
        if (size != null) {
            path.append(sep).append("size=").append(size);
        }
        return rest.exchange(path.toString(), HttpMethod.GET, new HttpEntity<>(null, json()), JsonNode.class);
    }

    private JsonNode findFeedItem(ResponseEntity<JsonNode> feedResponse, long id) {
        for (JsonNode item : feedResponse.getBody().get("items")) {
            if (item.get("id").asLong() == id) {
                return item;
            }
        }
        return null;
    }

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

    private ResponseEntity<JsonNode> linkProduct(String token, long id, long productId) {
        return rest.exchange("/api/v1/shorts/" + id + "/products", HttpMethod.POST,
                new HttpEntity<>(Map.of("productId", productId), bearer(token)), JsonNode.class);
    }

    private ResponseEntity<JsonNode> unlinkProduct(String token, long id, long productId) {
        return rest.exchange("/api/v1/shorts/" + id + "/products/" + productId, HttpMethod.DELETE,
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
