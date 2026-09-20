package com.beomsu.becommerce.order.catalog;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

// 하위 패키지가 같은 모듈 안에서 참조하므로 public 이다. 모듈 밖 접근은 package-private 이 아니라
// ModularityTests 의 allowedDependencies 가 막는다.
public interface ProductRepository extends JpaRepository<Product, Long> {

    /** 카테고리별 목록 — 정렬은 Pageable의 Sort로 받는다. */
    Page<Product> findByCategoryCode(String categoryCode, Pageable pageable);

    /** 중분류별 목록 — 대분류 × 상품 종류 조합 노드(V56). */
    Page<Product> findBySubcategoryCode(String subcategoryCode, Pageable pageable);

    /** 상품명·브랜드 부분 일치 검색. MySQL 기본 콜레이션이 대소문자를 무시해 IgnoreCase와 결과가 같다. */
    Page<Product> findByNameContainingOrBrandContaining(String keyword, String sameKeyword, Pageable pageable);

    /** 홈 큐레이션 — 추천 상품. */
    Page<Product> findByFeaturedTrue(Pageable pageable);

    long countByCategoryCode(String categoryCode);

    long countBySubcategoryCode(String subcategoryCode);
}
