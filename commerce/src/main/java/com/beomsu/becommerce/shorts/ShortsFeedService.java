package com.beomsu.becommerce.shorts;

import com.beomsu.becommerce.order.ProductCatalogFacts;
import com.beomsu.becommerce.order.ProductCatalogFacts.ProductCardFacts;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 숏폼 피드 조회(R26) — 로그인 없이 누구나 부를 수 있는 공개 읽기 진입점.
 *
 * <p>{@code READY} 상태만, id 내림차순(=최신순 — IDENTITY라 생성 순서와 같다)으로 커서 기반
 * 페이지를 돌려준다. 한 쪽 조립(상한 자르기·다음 커서 계산·READY 재확인)은 DB를 모르는
 * {@link ShortsFeedPage}가 하고, 여기서는 쿼리와 상품 카드 합치기만 한다.
 */
@Service
@Transactional(readOnly = true)
public class ShortsFeedService {

    /** 명세는 "한 번에 개수"의 정확한 상한을 못박지 않아, 요청에 없으면 이 값을 쓴다(가정). */
    static final int DEFAULT_PAGE_SIZE = 10;
    /** 과도한 size 요청으로 한 번에 다 긁어가는 것을 막는 상한(카탈로그 목록과 같은 방어). */
    static final int MAX_PAGE_SIZE = 50;

    private final ShortVideoRepository repository;
    private final ProductCatalogFacts productCatalogFacts;

    public ShortsFeedService(ShortVideoRepository repository, ProductCatalogFacts productCatalogFacts) {
        this.repository = repository;
        this.productCatalogFacts = productCatalogFacts;
    }

    /**
     * 피드 한 쪽. {@code cursor}가 null이면 첫 쪽(가장 최신부터), 아니면 그 id보다 작은 것부터.
     * {@code size}가 null이거나 범위 밖이면 {@link #DEFAULT_PAGE_SIZE}로 보정한다.
     */
    public ShortsFeedPageView feed(Long cursor, Integer size) {
        int pageSize = clampSize(size);
        Pageable pageSizePlusOne = PageRequest.of(0, pageSize + 1);
        List<ShortVideo> fetched = cursor == null
                ? repository.findByStatusOrderByIdDesc(ShortVideoStatus.READY, pageSizePlusOne)
                : repository.findByStatusAndIdLessThanOrderByIdDesc(ShortVideoStatus.READY, cursor, pageSizePlusOne);

        ShortsFeedPage.Result page = ShortsFeedPage.assemble(fetched, pageSize);

        Map<Long, ProductCardFacts> cardsById = new HashMap<>();
        List<Long> allLinkedIds = page.items().stream().flatMap(v -> v.getLinkedProductIds().stream()).toList();
        for (ProductCardFacts card : productCatalogFacts.findAll(allLinkedIds)) {
            cardsById.put(card.productId(), card);
        }

        List<ShortsFeedItemView> items = page.items().stream()
                .map(v -> ShortsFeedItemView.from(v, v.getLinkedProductIds().stream()
                        .map(cardsById::get).filter(Objects::nonNull).toList()))
                .toList();
        return new ShortsFeedPageView(items, page.nextCursor(), page.hasNext());
    }

    private static int clampSize(Integer size) {
        if (size == null) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    }
}
