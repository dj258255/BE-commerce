package com.beomsu.becommerce.live;

import com.beomsu.becommerce.order.ProductCatalogFacts;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

/**
 * {@link ProductLookup}(media가 정의한 포트)의 commerce 쪽 구현(R8, R32·ADR-080) —
 * {@code ShortsProductLookupAdapter}와 같은 자리(split package, 이 파일만 commerce
 * Gradle 모듈에 physically 있다). order가 공개한 읽기 전용 API를 감싼다 — order는 이
 * 클래스나 media(live)를 전혀 모른다.
 */
@Component
class LiveProductLookupAdapter implements ProductLookup {

    private final ProductCatalogFacts productCatalogFacts;

    LiveProductLookupAdapter(ProductCatalogFacts productCatalogFacts) {
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
