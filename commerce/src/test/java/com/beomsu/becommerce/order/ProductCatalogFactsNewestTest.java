package com.beomsu.becommerce.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.beomsu.becommerce.order.catalog.CatalogQueryService;
import com.beomsu.becommerce.order.catalog.ProductRepository;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/** 홈 2쪽의 신상품 채우기(#284): 순서는 그대로, 전체 개수는 세지 않는다. */
class ProductCatalogFactsNewestTest {

    @Test
    @DisplayName("대분류 신상품은 개수를 세지 않는 조회로, 신상품순(created_at · product_id 내림차순) 한 쪽만 읽는다")
    void newestInCategoryDoesNotCount() {
        ProductRepository products = mock(ProductRepository.class);
        when(products.findIdsByCategoryCode(eq("kids"), any())).thenReturn(List.of(30L, 20L, 10L));
        ProductCatalogFacts facts = new ProductCatalogFacts(products, mock(CatalogQueryService.class));

        assertThat(facts.newestInCategory("kids", 24)).containsExactly(30L, 20L, 10L);

        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(products).findIdsByCategoryCode(eq("kids"), page.capture());
        assertThat(page.getValue().getPageSize()).isEqualTo(24);
        assertThat(page.getValue().getSort()).isEqualTo(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("productId")));
        // 목록 검색(Page)은 count 를 같이 돌린다 — 이 경로에서는 부르지 않는다
        verify(products, never()).search(any(), any(), any(), any(), any(), any(), any(), any());
    }
}
