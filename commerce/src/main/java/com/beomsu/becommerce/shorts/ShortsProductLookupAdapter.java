package com.beomsu.becommerce.shorts;

import com.beomsu.becommerce.order.ProductCatalogFacts;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

/**
 * {@link ProductLookup}(media가 정의한 포트)의 commerce 쪽 구현(R32, ADR-080).
 *
 * <p>이 파일 하나만 commerce Gradle 모듈에 physically 남아 있다 — {@code shorts} 패키지의
 * 나머지는 전부 {@code media}에 있다(같은 패키지가 두 Gradle 모듈에 걸쳐 있는 split package다,
 * Java 모듈 시스템을 쓰지 않아 합법이다). media는 이 클래스의 존재를 모른다 — Spring이
 * {@link ProductLookup} 타입으로 주입할 때만 등장한다.
 *
 * <p>order가 공개한 읽기 전용 API({@link ProductCatalogFacts}, ADR-018)를 감싼다 — wishlist가
 * 쓰는 것과 같은 경로다. order는 이 클래스나 media를 전혀 모른다(의존 방향은
 * shorts → order 하나뿐, package-info.java의 allowedDependencies와 일치).
 */
@Component
class ShortsProductLookupAdapter implements ProductLookup {

    private final ProductCatalogFacts productCatalogFacts;

    ShortsProductLookupAdapter(ProductCatalogFacts productCatalogFacts) {
        this.productCatalogFacts = productCatalogFacts;
    }

    @Override
    public boolean exists(long productId) {
        return productCatalogFacts.exists(productId);
    }

    @Override
    public List<Product> findAll(Collection<Long> productIds) {
        return productCatalogFacts.findAll(productIds).stream()
                .map(card -> new Product(card.productId(), card.name(), card.price()))
                .toList();
    }
}
