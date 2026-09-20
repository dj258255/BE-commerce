package com.beomsu.becommerce.wishlist.internal;

import com.beomsu.becommerce.order.ProductCatalogFacts;
import com.beomsu.becommerce.order.ProductCatalogFacts.ProductCardFacts;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 위시리스트 애플리케이션 서비스 — 찜의 추가·삭제·조회.
 *
 * <p><b>멱등이 계약이다.</b> 추가는 두 번 눌러도 1건이고, 삭제는 없는 것을 지워도 성공이다.
 * 하트 버튼은 실수로 두 번 눌리기 쉬운 표면이라 "두 번째 요청이 실패"하면 UX가 아니라 계약이 깨진다.
 *
 * <p><b>소유권 검증이 없다.</b> 모든 진입점이 인증 principal의 userId로만 스코프되므로 남의
 * 위시리스트를 가리키는 경로 자체가 없다 — {@code requireOwned}가 필요한 다른 모듈과 다른 점이다.
 *
 * <p>남은 경계(정직하게): 서로 다른 두 요청이 <b>동시에</b> 같은 상품을 찜하면, 사전 조회를 둘 다
 * 통과하고 두 번째 INSERT가 유니크 제약에 막힌다. 그때 DB는 1건으로 유지되지만 늦은 쪽은
 * {@code 409}를 받는다({@code GlobalExceptionHandler}가 무결성 위반을 409로 변환한다).
 * 순차 요청(연타 포함)은 사전 조회가 흡수하므로 이 창은 마이크로초 단위다.
 */
@Service
@Transactional
public class WishlistService {

    private final WishlistRepository wishlistRepository;
    private final ProductCatalogFacts productCatalogFacts;

    public WishlistService(WishlistRepository wishlistRepository, ProductCatalogFacts productCatalogFacts) {
        this.wishlistRepository = wishlistRepository;
        this.productCatalogFacts = productCatalogFacts;
    }

    /**
     * 찜 추가 — 멱등. 이미 찜한 상품이면 <b>그때의 {@code addedAt}을 그대로</b> 돌려준다.
     *
     * <p>없는 상품은 {@code PRODUCT_NOT_FOUND}(404)다. 이 검사는 카탈로그의 공개 읽기 포트
     * ({@link ProductCatalogFacts})로만 한다 — 위시리스트가 상품 테이블을 직접 열지 않는다.
     */
    public WishlistView add(long userId, long productId) {
        ProductCardFacts card = productCatalogFacts.find(productId)
                .orElseThrow(() -> WishlistException.productNotFound(productId));
        WishlistItem item = wishlistRepository.findByUserIdAndProductId(userId, productId)
                .orElseGet(() -> wishlistRepository.save(WishlistItem.of(userId, productId)));
        return WishlistView.of(productId, card, item.getCreatedAt());
    }

    /**
     * 찜 삭제 — 멱등. 찜하지 않은 상품을 지워도 성공이다(0행 삭제).
     *
     * <p>없는 상품 id로도 성공한다. 삭제의 목적은 "이 상품이 내 찜에 없게 만드는 것"이고,
     * 그 상태는 이미 참이기 때문이다.
     */
    public void remove(long userId, long productId) {
        wishlistRepository.deleteByUserIdAndProductId(userId, productId);
    }

    /**
     * 내 찜 목록 — 최근에 찜한 것이 먼저.
     *
     * <p>카드 값은 상품 id 묶음으로 <b>한 번에</b> 읽어 N+1을 피한다. 상품이 사라진 찜은
     * {@code available=false}로 남긴다.
     */
    @Transactional(readOnly = true)
    public List<WishlistView> list(long userId) {
        List<WishlistItem> rows = wishlistRepository.findByUserIdOrderByIdDesc(userId);
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> productIds = rows.stream().map(WishlistItem::getProductId).toList();
        Map<Long, ProductCardFacts> cards = new HashMap<>();
        for (ProductCardFacts card : productCatalogFacts.findAll(productIds)) {
            cards.put(card.productId(), card);
        }
        return rows.stream()
                .map(row -> WishlistView.of(row.getProductId(), cards.get(row.getProductId()), row.getCreatedAt()))
                .toList();
    }
}
