package com.beomsu.becommerce.order.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CategoryRepository extends JpaRepository<Category, String> {

    /** 전체를 노출 순서대로. 트리를 만들 때 부모·자식을 한 번에 읽어 메모리에서 묶는다. */
    List<Category> findAllByOrderBySortOrderAsc();

    /** 대분류만 — 중분류 없는 기본 화면이 쓴다. */
    List<Category> findByParentCodeIsNullOrderBySortOrderAsc();
}
