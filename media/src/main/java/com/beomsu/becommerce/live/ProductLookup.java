package com.beomsu.becommerce.live;

import java.util.Collection;
import java.util.List;

/**
 * media(live)가 상품 정보를 읽기 위한 포트(R8, R32·ADR-080) — {@code shorts.ProductLookup}과
 * 같은 모양을 일부러 복제한다. live가 shorts 모듈에 의존하게 하지 않으려는 것이다(둘 다
 * media의 형제 모듈이고, package-info.java의 allowedDependencies가 {@code shared}·
 * {@code order}뿐이다 — shorts는 거기 없다). 구현(어댑터)은 commerce 쪽에 둔다(같은 패키지
 * {@code com.beomsu.becommerce.live}지만 물리적으로 commerce 모듈에 있는
 * {@code LiveProductLookupAdapter}).
 */
public interface ProductLookup {

    /** 이 상품이 카탈로그에 존재하는가(R8: 고정하려는 상품의 실존 확인). */
    boolean exists(long productId);

    /** 여러 상품의 이름·가격. 카탈로그에 없는 상품은 결과에서 빠진다. */
    List<Product> findAll(Collection<Long> productIds);

    record Product(long productId, String name, long price) {
    }
}
