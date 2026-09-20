package com.beomsu.becommerce.wishlist.internal;

import com.beomsu.becommerce.shared.DomainException;

/**
 * 위시리스트 도메인 예외. code는 10-API-스펙 문서의 에러 코드 체계와 일치한다.
 *
 * <p><b>새 코드를 만들지 않는다.</b> 상품이 없으면 카탈로그와 <b>같은 코드</b>
 * ({@code PRODUCT_NOT_FOUND})를 쓴다 — 같은 상황인데 경로마다 다른 코드를 내보내면 클라이언트가
 * 분기할 근거가 둘로 갈린다. 코드가 같으므로 {@code GlobalExceptionHandler}가 이미 404로 매핑한다.
 *
 * <p>소유권 위반({@code WISHLIST_FORBIDDEN}) 코드는 <b>없다</b>. 찜은 경로에 userId를 받지 않고
 * 인증 principal로만 스코프되므로 "남의 위시리스트"에 접근할 경로 자체가 없다(그 검증은
 * {@code WishlistIntegrationTest}가 테스트로 고정한다).
 */
public class WishlistException extends DomainException {

    public WishlistException(String code, String message) {
        super(code, message);
    }

    public static WishlistException productNotFound(long productId) {
        return new WishlistException("PRODUCT_NOT_FOUND", "상품을 찾을 수 없습니다: " + productId);
    }
}
