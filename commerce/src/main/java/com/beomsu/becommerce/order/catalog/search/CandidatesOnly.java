package com.beomsu.becommerce.order.catalog.search;

/** 엔진이 필터를 지원해도 쓰지 않게 가린다 — 같은 엔진에서 후보 자르기를 재기 위한 실측용(#244). */
final class CandidatesOnly implements ProductSearch {

    private final ProductSearch delegate;

    CandidatesOnly(ProductSearch delegate) {
        this.delegate = delegate;
    }

    @Override
    public SearchPage search(String query, int page, int size) {
        return delegate.search(query, page, size);
    }

    @Override
    public String engine() {
        return delegate.engine();
    }

    @Override
    public boolean ranksByRelevance() {
        return delegate.ranksByRelevance();
    }
}
