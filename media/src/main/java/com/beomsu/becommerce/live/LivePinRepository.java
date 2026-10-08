package com.beomsu.becommerce.live;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

interface LivePinRepository extends JpaRepository<LivePin, Long> {

    /** 방송당 행이 하나다(R8) — {@code uk_live_pins_broadcast}. */
    Optional<LivePin> findByBroadcastId(long broadcastId);
}
