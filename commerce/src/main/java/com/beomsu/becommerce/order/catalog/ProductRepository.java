package com.beomsu.becommerce.order.catalog;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

// 하위 패키지가 같은 모듈 안에서 참조하므로 public 이다. 모듈 밖 접근은 package-private 이 아니라
// ModularityTests 의 allowedDependencies 가 막는다.
public interface ProductRepository extends JpaRepository<Product, Long> {

    /**
     * 목록·필터를 <b>하나의 질의</b>로 처리한다. 여섯 개의 선택 필터(카테고리·중분류·색상·종류·
     * 최소가·최대가)에 추천까지 더하면 파생 메서드로는 2^7 가지가 필요하다 — 조합마다 이름을 짓고
     * 유지할 수 없다. null인 파라미터는 조건에서 스스로 빠지므로 하나로 충분하다.
     *
     * <p>정렬은 {@code Pageable}의 Sort로 받는다. {@code :featured}가 null이면 추천 필터가 없다.
     */
    @Query("select p from Product p where (:categoryCode is null or p.categoryCode = :categoryCode) "
            + "and (:subcategoryCode is null or p.subcategoryCode = :subcategoryCode) "
            + "and (:colourCode is null or p.colourCode = :colourCode) "
            + "and (:productType is null or p.productType = :productType) "
            + "and (:minPrice is null or p.price >= :minPrice) "
            + "and (:maxPrice is null or p.price <= :maxPrice) "
            + "and (:featured is null or p.featured = :featured)")
    Page<Product> search(@Param("categoryCode") String categoryCode,
                         @Param("subcategoryCode") String subcategoryCode,
                         @Param("colourCode") String colourCode,
                         @Param("productType") String productType,
                         @Param("minPrice") Long minPrice,
                         @Param("maxPrice") Long maxPrice,
                         @Param("featured") Boolean featured,
                         Pageable pageable);

    /**
     * 대분류의 상품 id 한 쪽(#284). 목록 반환이라 <b>전체 개수를 세지 않는다</b> — {@link #search} 는 {@code Page} 라
     * count 가 같이 돌고, 홈 2쪽의 신상품 채우기는 그 개수를 쓰지 않는다. 정렬은 {@code Pageable} 로 받는다.
     */
    @Query("select p.productId from Product p where p.categoryCode = :categoryCode")
    List<Long> findIdsByCategoryCode(@Param("categoryCode") String categoryCode, Pageable pageable);

    /** 상품명·브랜드 부분 일치 검색. MySQL 기본 콜레이션이 대소문자를 무시해 IgnoreCase와 결과가 같다. */
    Page<Product> findByNameContainingOrBrandContaining(String keyword, String sameKeyword, Pageable pageable);

    /**
     * 필드를 넓힌 부분 일치 검색 — 상품명·브랜드·종류·설명(#236). 엔진과 <b>같은 필드</b>를 보게 해서
     * "엔진이 이긴 폭"에서 "필드를 넓힌 효과"를 떼어 내려는 비교 대상이다. 앞에 %가 붙어 인덱스를 못 탄다.
     */
    @Query(value = "select p.productId from Product p where p.name like concat('%', :q, '%') "
            + "or p.brand like concat('%', :q, '%') or p.productType like concat('%', :q, '%') "
            + "or p.description like concat('%', :q, '%')",
            countQuery = "select count(p) from Product p where p.name like concat('%', :q, '%') "
                    + "or p.brand like concat('%', :q, '%') or p.productType like concat('%', :q, '%') "
                    + "or p.description like concat('%', :q, '%')")
    Page<Long> searchIdsAcrossFields(@Param("q") String q, Pageable pageable);

    /** 검색 엔진이 고른 후보 안에서 사용자가 고른 정렬로 한 페이지를 자른다. */
    Page<Product> findByProductIdIn(Collection<Long> productIds, Pageable pageable);

    /**
     * 색상 패싯 — 색상별 상품 수. <b>색상 필터를 뺀</b> 나머지 필터(카테고리·종류·가격·추천)만 적용한다.
     * 그래야 한 색을 고른 상태에서도 다른 색의 개수가 그대로 보인다.
     */
    @Query("select p.colourCode as code, p.colourName as name, count(p) as count from Product p "
            + "where p.colourCode is not null "
            + "and (:categoryCode is null or p.categoryCode = :categoryCode) "
            + "and (:subcategoryCode is null or p.subcategoryCode = :subcategoryCode) "
            + "and (:productType is null or p.productType = :productType) "
            + "and (:minPrice is null or p.price >= :minPrice) "
            + "and (:maxPrice is null or p.price <= :maxPrice) "
            + "and (:featured is null or p.featured = :featured) "
            + "group by p.colourCode, p.colourName order by count(p) desc")
    List<FacetCount> colourFacet(@Param("categoryCode") String categoryCode,
                                 @Param("subcategoryCode") String subcategoryCode,
                                 @Param("productType") String productType,
                                 @Param("minPrice") Long minPrice,
                                 @Param("maxPrice") Long maxPrice,
                                 @Param("featured") Boolean featured);

    /**
     * 상품 종류 패싯 — 종류별 상품 수. <b>종류 필터를 뺀</b> 나머지 필터(카테고리·색상·가격·추천)만
     * 적용한다(색상 패싯과 같은 이유).
     */
    @Query("select p.productType as code, p.productType as name, count(p) as count from Product p "
            + "where p.productType is not null "
            + "and (:categoryCode is null or p.categoryCode = :categoryCode) "
            + "and (:subcategoryCode is null or p.subcategoryCode = :subcategoryCode) "
            + "and (:colourCode is null or p.colourCode = :colourCode) "
            + "and (:minPrice is null or p.price >= :minPrice) "
            + "and (:maxPrice is null or p.price <= :maxPrice) "
            + "and (:featured is null or p.featured = :featured) "
            + "group by p.productType order by count(p) desc")
    List<FacetCount> typeFacet(@Param("categoryCode") String categoryCode,
                               @Param("subcategoryCode") String subcategoryCode,
                               @Param("colourCode") String colourCode,
                               @Param("minPrice") Long minPrice,
                               @Param("maxPrice") Long maxPrice,
                               @Param("featured") Boolean featured);

    long countByCategoryCode(String categoryCode);

    long countBySubcategoryCode(String subcategoryCode);
}
