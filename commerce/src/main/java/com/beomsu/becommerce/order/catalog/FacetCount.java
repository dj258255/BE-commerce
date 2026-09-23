package com.beomsu.becommerce.order.catalog;

/**
 * 패싯 한 칸 — 한 값(코드·이름)과 그 값에 해당하는 상품 수.
 *
 * <p>패싯 질의가 돌려주는 <b>인터페이스 투영</b>이다. 별도 클래스로 받지 않는 이유: 질의 결과는
 * (코드, 이름, 개수) 세 값뿐이라 매핑 클래스를 두면 상용구만 늘고, JPQL의 별칭이 그대로 getter에
 * 붙어 두 벌을 맞출 필요가 없다. 화면은 이걸 그대로 체크박스·옵션으로 그린다.
 */
public interface FacetCount {

    /** 패싯 값의 코드 — 색상이면 {@code colour_code}, 종류면 {@code product_type} 원문. */
    String getCode();

    /** 화면에 보여 줄 이름 — 색상이면 한국어 이름, 종류면 원문(영어). */
    String getName();

    /** 이 값에 해당하는 상품 수. 자기 축을 뺀 다른 필터가 적용된 뒤의 개수다. */
    long getCount();
}
