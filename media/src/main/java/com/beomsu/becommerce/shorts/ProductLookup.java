package com.beomsu.becommerce.shorts;

import java.util.Collection;
import java.util.List;

/**
 * media(shorts)가 상품 정보를 읽기 위한 포트(R32, ADR-080).
 *
 * <p>media는 commerce의 카탈로그({@code order.ProductCatalogFacts})를 전혀 모른다 — 알면
 * commerce(이미 media에 의존)와 순환이 생긴다. 그래서 media가 이 포트를 스스로 정의하고
 * (의존성 역전), 구현(어댑터)은 commerce 쪽에 둔다 — 같은 패키지({@code com.beomsu.becommerce.shorts})
 * 지만 물리적으로 commerce 모듈에 있는 {@code ShortsProductLookupAdapter}가 그것이다.
 * Spring이 타입으로 주입하므로 media 코드는 그 어댑터가 존재하는지도 몰라도 된다.
 */
public interface ProductLookup {

    /** 이 상품이 카탈로그에 존재하는가(R25: 연결하려는 상품 실존 확인). */
    boolean exists(long productId);

    /** 여러 상품의 이름·가격. 카탈로그에 없는 상품은 결과에서 빠진다. */
    List<Product> findAll(Collection<Long> productIds);

    /** 상품 카드에 필요한 최소 값 — R25 연결 상품 요약과 같은 모양. */
    record Product(long productId, String name, long price) {
    }
}
