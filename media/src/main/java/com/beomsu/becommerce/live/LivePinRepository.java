package com.beomsu.becommerce.live;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

interface LivePinRepository extends JpaRepository<LivePin, Long> {

    /** 방송당 행이 하나다(R8) — {@code uk_live_pins_broadcast}. */
    Optional<LivePin> findByBroadcastId(long broadcastId);

    /** 지금 고정된 상품이 있는 모든 방송(R13) — {@code LiveOrderHoldReconciler}가 주기마다 훑는 대상. */
    List<LivePin> findByProductIdIsNotNull();
}
