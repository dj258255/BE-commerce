package com.beomsu.becommerce.live;

import com.beomsu.becommerce.order.SpecialPriceOrderPlacement;
import org.springframework.stereotype.Component;

/**
 * {@link OrderPlacement}(media가 정의한 포트)의 commerce 쪽 구현(R10·R11, R32·ADR-085) —
 * {@code LiveProductLookupAdapter}와 같은 자리(split package, 이 파일만 commerce Gradle
 * 모듈에 physically 있다). order가 공개한 쓰기 전용 진입점 하나({@link SpecialPriceOrderPlacement})를
 * 그대로 감싼다 — order는 이 클래스나 media(live)를 전혀 모른다.
 */
@Component
class LiveOrderPlacementAdapter implements OrderPlacement {

    private final SpecialPriceOrderPlacement specialPriceOrderPlacement;

    LiveOrderPlacementAdapter(SpecialPriceOrderPlacement specialPriceOrderPlacement) {
        this.specialPriceOrderPlacement = specialPriceOrderPlacement;
    }

    @Override
    public PlacedOrder place(long userId, long productId, long unitPrice, String idempotencyKey) {
        SpecialPriceOrderPlacement.PlacedOrder result =
                specialPriceOrderPlacement.place(userId, productId, unitPrice, idempotencyKey);
        return new PlacedOrder(result.orderNo(), result.totalAmount(), result.expiresAt());
    }
}
