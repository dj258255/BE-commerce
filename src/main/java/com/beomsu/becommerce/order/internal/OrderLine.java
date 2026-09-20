package com.beomsu.becommerce.order.internal;

import com.beomsu.becommerce.order.catalog.Product;


/**
 * 주문 생성 요청의 한 줄(항목).
 *
 * <p>클라이언트는 <b>무엇을 몇 개 살지</b>(productId, quantity)만 보낸다. 가격은 절대 받지 않는다 —
 * 서버가 {@link Product} 카탈로그에서 조회한 가격으로 금액을 계산해야, 금액 위변조 검증의 기준값이
 * 신뢰할 수 있다. (클라이언트가 가격을 보내면 1원 결제 같은 조작이 가능해진다.)
 */
public record OrderLine(long productId, int quantity) {
}
