package com.beomsu.becommerce.recommendation.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 인기 통계 한 줄 — <b>실제 거래로 센 인기</b>(#198②).
 *
 * <p>오프라인 배치가 만든다(`personalization/pipeline/export_popular.py`, 3,178만 거래). 서빙은
 * 결과만 읽는다 — 요청마다 셀 수 없는 값이다.
 *
 * <p>{@code computedAt} 은 <b>데이터 기준일</b>이다(벽시계가 아니다). 이 데이터는 2020-09-22 에
 * 끝나므로 "최근 7일"은 그 날의 7일이다 — 화면이 "요즘 인기"라고 말할 때 그 "요즘"이 언제인지
 * 값이 스스로 밝히게 하려는 것이다.
 *
 * <p>컬럼명이 {@code window_kind}·{@code rank_no} 인 이유: MySQL 8 에서 {@code window}·{@code rank}
 * 는 <b>예약어</b>다.
 */
@Entity
@Table(name = "product_popularity")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class ProductPopularity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private long productId;

    @Column(name = "window_kind", nullable = false, length = 20)
    private String windowKind;

    @Column(name = "rank_no", nullable = false)
    private int rankNo;

    @Column(name = "purchase_count", nullable = false)
    private long purchaseCount;

    @Column(name = "computed_at", nullable = false)
    private LocalDate computedAt;
}
