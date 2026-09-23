package com.beomsu.becommerce.order.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 상품 리뷰 한 건 — <b>지금 이 표에 들어 있는 것은 전부 합성이다</b>(#168).
 *
 * <p>H&M 코퍼스에 리뷰가 없고, Amazon 리뷰는 다른 카탈로그라 붙일 수 없다. 그래서 화면을 채우려면
 * 만들 수밖에 없고, 만들면 밝혀야 한다 — {@code source} 가 그 표시이고 화면·API 가 그 값을 문구로 쓴다.
 *
 * <p><b>평점 평균은 어디에도 저장하지 않는다.</b> 비정규화하는 순간 그 값이 상품의 품질 신호처럼
 * 읽히고, 그때부터 화면은 "실제 평점"처럼 보인다. 없는 것이 이 설계의 일부다(ADR-046).
 */
@Entity
@Table(name = "product_reviews")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class ProductReview {

    /** 지금 허용되는 유일한 출처. 실데이터가 생기면 값을 늘리기 전에 집계 경로를 다시 설계한다. */
    static final String SOURCE_SYNTHETIC = "SYNTHETIC";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private long productId;

    @Column(nullable = false, length = 20)
    private String source;

    @Column(nullable = false)
    private int rating;

    @Column(nullable = false, length = 500)
    private String body;

    @Column(name = "author_label", nullable = false, length = 60)
    private String authorLabel;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    boolean synthetic() {
        return SOURCE_SYNTHETIC.equals(source);
    }
}
