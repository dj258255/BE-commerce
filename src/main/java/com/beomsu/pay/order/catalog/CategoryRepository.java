package com.beomsu.pay.order.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CategoryRepository extends JpaRepository<Category, String> {

    /** 노출 순서대로. 순서는 DB(sort_order)가 정한다. */
    List<Category> findAllByOrderBySortOrderAsc();
}
