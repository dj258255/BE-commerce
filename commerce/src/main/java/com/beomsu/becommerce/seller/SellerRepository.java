package com.beomsu.becommerce.seller;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SellerRepository extends JpaRepository<Seller, Long> {

    Optional<Seller> findByBusinessNumber(String businessNumber);

    /**
     * 재스크리닝 대상. <b>명단은 갱신되므로 어제 통과한 판매자가 오늘 걸릴 수 있다.</b>
     * 차단된 쪽은 뺀다 — 이미 막혀 있고 푸는 것은 사람이 한다.
     */
    List<Seller> findByStatusIn(List<SellerStatus> statuses, Pageable page);
}
