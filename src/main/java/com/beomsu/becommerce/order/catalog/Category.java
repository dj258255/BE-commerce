package com.beomsu.becommerce.order.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 상품 카테고리 — 쇼핑몰의 탐색 축. **2단계**다.
 *
 * <p>{@code name}은 사람이 읽는 이름이고, {@code code}는 화면과 API가 쓰는 안정 식별자다.
 * 이름을 바꿔도 URL·필터가 깨지지 않도록 둘을 나눴다({@code /category.html?code=ladieswear}).
 * 정렬은 {@code sortOrder}로 고정해 노출 순서를 DB에서 정한다.
 *
 * <p>{@code parentCode}가 NULL이면 대분류, 값이 있으면 중분류다. 중분류는 **대분류 × 상품 종류의
 * 조합 노드**다 — H&M의 두 축이 직교하기 때문이다(V56 주석). {@code sortOrder}는 대분류는 전체에서,
 * 중분류는 **같은 부모 안에서** 순서를 뜻한다.
 *
 * <p>이름의 출처(H&M 원문)는 {@code source_name} 컬럼에 남지만 여기서는 매핑하지 않는다 —
 * 출처 추적은 데이터 감사에 쓰이는 정보이고 화면·API가 쓰지 않는다.
 */
@Entity
@Table(name = "categories")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Category {

    @Id
    @Column(length = 40)
    private String code;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(length = 300)
    private String description;

    @Column(nullable = false)
    private int sortOrder;

    @Column(length = 40)
    private String parentCode;

    private Category(String code, String name, String description, int sortOrder, String parentCode) {
        this.code = code;
        this.name = name;
        this.description = description;
        this.sortOrder = sortOrder;
        this.parentCode = parentCode;
    }

    /** 대분류. */
    public static Category of(String code, String name, String description, int sortOrder) {
        return new Category(code, name, description, sortOrder, null);
    }

    /** 중분류 — {@code parentCode}가 대분류를 가리킨다. */
    public static Category of(String code, String name, String description, int sortOrder, String parentCode) {
        return new Category(code, name, description, sortOrder, parentCode);
    }
}
