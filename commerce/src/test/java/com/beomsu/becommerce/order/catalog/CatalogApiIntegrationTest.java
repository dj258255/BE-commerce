package com.beomsu.becommerce.order.catalog;

import com.beomsu.becommerce.testsupport.SharedContainers;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 공개 읽기 표면(카탈로그)의 <b>계약</b>을 실제 기동으로 고정한다.
 *
 * <p>이 표면에는 통합 테스트가 없었다 — 로그인 없이 열려 있는 가장 넓은 문인데도 그랬다.
 * 여기서 지키려는 것은 <b>#168 의 리뷰 계약</b>이다:
 * <ul>
 *   <li>리뷰가 합성이라는 사실을 <b>응답이 밝힌다</b>({@code reviewsSynthetic}) — 화면이 배지를
 *       그릴 근거가 응답에 있어야 한다</li>
 *   <li>없는 상품은 주문 경로와 같은 코드로 404 다</li>
 * </ul>
 *
 * <p>합성 리뷰가 실데이터처럼 집계되지 않는지는 {@link ReviewSyntheticGuardTest} 가 구조로 막는다.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("카탈로그 공개 표면 — 리뷰는 합성임을 밝히고, 없는 상품은 404 다")
class CatalogApiIntegrationTest {

    @DynamicPropertySource
    static void datasourceAndRedis(DynamicPropertyRegistry registry) {
        SharedContainers.register(registry, "CatalogApi");
    }

    @Autowired
    TestRestTemplate rest;

    @Test
    @DisplayName("공개 읽기 — 로그인 없이 상세가 열리고, 리뷰가 합성이라는 사실을 응답이 밝힌다")
    void detailDeclaresSyntheticReviews() {
        long productId = 1L;
        ResponseEntity<JsonNode> response = rest.getForEntity("/api/v1/products/" + productId, JsonNode.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        // **배지 근거가 응답에 있다** — 프론트가 추측하게 두면 언젠가 배지를 잊는다.
        assertThat(body.get("reviewsSynthetic").asBoolean()).isTrue();
        assertThat(body.has("reviews")).isTrue();
        // 평점 **집계**는 어디에도 없다(합성 리뷰로 "4.3점"을 만들지 않는다).
        assertThat(body.has("averageRating")).isFalse();
        assertThat(body.has("rating")).isFalse();
    }

    @Test
    @DisplayName("없는 상품은 404 — 주문 경로와 같은 코드를 쓴다")
    void unknownProductIsNotFound() {
        assertThat(rest.getForEntity("/api/v1/products/999999999", String.class)
                .getStatusCode().value()).isEqualTo(404);
    }
}
