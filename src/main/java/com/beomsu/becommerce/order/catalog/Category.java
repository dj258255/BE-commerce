package com.beomsu.becommerce.order.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 상품 카테고리 — 쇼핑몰의 탐색 축.
 *
 * <p>{@code name}은 사람이 읽는 이름이고, {@code code}는 화면과 API가 쓰는 안정 식별자다.
 * 이름을 바꿔도 URL·필터가 깨지지 않도록 둘을 나눴다({@code /category.html?code=digital}).
 * 정렬은 {@code sortOrder}로 고정해 노출 순서를 DB에서 정한다.
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

    private Category(String code, String name, String description, int sortOrder) {
        this.code = code;
        this.name = name;
        this.description = description;
        this.sortOrder = sortOrder;
    }

    public static Category of(String code, String name, String description, int sortOrder) {
        return new Category(code, name, description, sortOrder);
    }
}
