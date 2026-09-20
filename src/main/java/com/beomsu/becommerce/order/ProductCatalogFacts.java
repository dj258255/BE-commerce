package com.beomsu.becommerce.order;

import com.beomsu.becommerce.order.catalog.CatalogQueryService;
import com.beomsu.becommerce.order.catalog.Product;
import com.beomsu.becommerce.order.catalog.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
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
                        inStock.contains(product.getProductId())))
                .toList();
    }

    /**
     * 카드에 그릴 값만 담은 읽기 전용 뷰.
     *
     * <p>{@code description}·{@code categoryCode} 같은 탐색 전용 속성은 넣지 않는다 —
     * 필요한 쪽이 생기면 그때 그 필드를 더한다. 처음부터 다 내주면 계약이 이유 없이 넓어진다.
     */
    public record ProductCardFacts(long productId, String name, long price,
                                   String brand, String imageUrl, boolean inStock) {
    }
}
