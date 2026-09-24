package com.beomsu.becommerce.order;

import com.beomsu.becommerce.order.catalog.CatalogQueryService;
import com.beomsu.becommerce.order.catalog.Product;
import com.beomsu.becommerce.order.catalog.ProductRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 카탈로그가 <b>다른 모듈에 내주는 상품 사실</b> (ADR-018).
 *
 * <p><b>왜 이 클래스가 있나</b>: 상품({@link Product}·{@code ProductRepository})은 {@code order.catalog}
 * 안에 있고 그 패키지는 order 모듈의 <b>내부</b>다. 다른 모듈이 직접 import하면
 * {@code ModularityTests}가 경계 침투로 잡는다. 그렇다고 {@code ProductRepository}를 열어 주면
 * 조회뿐 아니라 {@code save()}까지 열려 가격의 서버 측 원천을 밖에서 고칠 수 있게 된다.
 *
 * <p>그래서 주문 도메인이 <b>무엇을 남에게 보여줄지 스스로 정해</b> 읽기 전용으로만 노출한다 —
 * {@link OrderTimelineFacts}와 같은 이유, 같은 방식이다. 엔티티를 그대로 넘기지 않고
 * 카드에 그릴 값만 담은 {@link ProductCardFacts}로 굳혀 내보낸다.
 *
 * <p>지금 쓰는 곳은 위시리스트(wishlist) 모듈이다 — 찜을 걸기 전에 상품 실존을 확인하고,
 * 찜 목록을 그릴 때 카드 값을 붙여야 한다. 가격은 여기서 읽기만 하고 바뀌지 않는다.
 */
@Service
public class ProductCatalogFacts {

    private final ProductRepository productRepository;
    private final CatalogQueryService catalogQueryService;

    ProductCatalogFacts(ProductRepository productRepository, CatalogQueryService catalogQueryService) {
        this.productRepository = productRepository;
        this.catalogQueryService = catalogQueryService;
    }

    /**
     * 카탈로그에 있는 <b>모든 상품 id</b> — 순서는 id 오름차순(결정적).
     *
     * <p><b>왜 이게 필요한가</b>: 홈 컴포저와 추천 코어는 "무엇을 추천할 수 있는가"의 후보 집합이
     * 필요하다. 그 집합의 원천은 커머스 카탈로그 하나여야 한다 — 추천이 자기만의 id 공간을 따로
     * 들고 있으면 홈이 그 id로 상품 카드를 그릴 수 없다(이름·가격이 없으니까). M7의 "제품 연결"이
     * 이 메서드다.
     *
     * <p>전체를 돌려주는 것이 부담이면 호출자가 자른다 — 계약을 좁게 잡아 두고 필요할 때 넓히는
     * 규칙(ADR-018)에 따라, 여기서는 "후보 전체"라는 사실만 내준다.
     */
    @Transactional(readOnly = true)
    public List<Long> allProductIds() {
        return productRepository.findAll().stream().map(Product::getProductId).sorted().toList();
    }

    /** 이 상품이 존재하는가. 없으면 {@code false} — 예외가 아니라 사실이다(찜 대상 검증). */
    @Transactional(readOnly = true)
    public boolean exists(long productId) {
        return productRepository.existsById(productId);
    }

    /** 상품 하나의 카드 값. 없으면 {@code empty}. */
    @Transactional(readOnly = true)
    public Optional<ProductCardFacts> find(long productId) {
        return productRepository.findById(productId)
                .map(product -> new ProductCardFacts(product.getProductId(), product.getName(),
                        product.getPrice(), product.getBrand(), product.getImageUrl(),
                        product.getCategoryCode(),
                        catalogQueryService.idsInStock(List.of(product.getProductId()))
                                .contains(product.getProductId())));
    }

    /**
     * 여러 상품의 카드 값. <b>없는 상품은 결과에서 빠진다</b> — 호출한 쪽이 "왜 안 왔는지"를
     * 판단하게 하려는 것이다. 재고는 id 묶음으로 <b>한 번에</b> 읽어 N+1을 피한다.
     */
    @Transactional(readOnly = true)
    public List<ProductCardFacts> findAll(Collection<Long> productIds) {
        if (productIds.isEmpty()) {
            return List.of();
        }
        Set<Long> inStock = catalogQueryService.idsInStock(productIds);
        return productRepository.findAllById(productIds).stream()
                .map(product -> new ProductCardFacts(product.getProductId(), product.getName(),
                        product.getPrice(), product.getBrand(), product.getImageUrl(),
                        product.getCategoryCode(),
                        inStock.contains(product.getProductId())))
                .toList();
    }

    /**
     * 한 대분류의 최신 상품 id — 홈 다음 쪽이 <b>인기 표에 거의 없는 대분류</b>의 행을 채울 때 쓴다(#237).
     * 인기 표(200행)에 아동복이 0개라, 사용자가 아동복을 봐도 그 행을 만들 재료가 없어 세션 신호가 조용히
     * 버려지던 것을 막는다. 순서는 목록 기본값(신상품순)과 같다.
     */
    @Transactional(readOnly = true)
    public List<Long> newestInCategory(String categoryCode, int limit) {
        // 개수를 세지 않는 조회(#284). 예전에는 목록 검색(Page)을 재사용해 대분류 전체 count 가 같이 돌았다
        return productRepository.findIdsByCategoryCode(categoryCode, PageRequest.of(0, Math.max(limit, 1),
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("productId"))));
    }

    /**
     * 대분류 코드 → 이름. 홈의 다음 쪽이 "여성복 인기" 같은 행 제목을 붙일 때 쓴다(#237).
     * 중분류는 넣지 않는다 — 카드가 대분류 코드만 들고 있다.
     */
    @Transactional(readOnly = true)
    public Map<String, String> topCategoryNames() {
        return catalogQueryService.topCategoryNames();   // 상품 수는 세지 않는다(#284)
    }

    /**
     * 카드에 그릴 값만 담은 읽기 전용 뷰.
     *
     * <p>{@code description} 같은 탐색 전용 속성은 넣지 않는다 — 필요한 쪽이 생기면 그때 그 필드를
     * 더한다. 처음부터 다 내주면 계약이 이유 없이 넓어진다.
     *
     * <p>{@code categoryCode} 는 <b>홈 컴포저가 필요해서 더했다</b>(M7). 페이지를 여러 행으로 나눌 때
     * "같은 대분류가 한 화면을 독점하지 않게" 하는 규칙이 이 값을 쓴다 — 그 규칙 없이는 홈이
     * 점수순 목록의 반복이 된다. <b>필요한 쪽이 생겼기 때문에 넓혔고, 그 이유를 여기 적는다.</b>
     */
    public record ProductCardFacts(long productId, String name, long price,
                                   String brand, String imageUrl, String categoryCode, boolean inStock) {
    }
}
