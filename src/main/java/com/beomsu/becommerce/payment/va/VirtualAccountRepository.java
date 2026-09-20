package com.beomsu.becommerce.payment.va;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

interface VirtualAccountRepository extends JpaRepository<VirtualAccount, Long> {

    Optional<VirtualAccount> findByPaymentKey(String paymentKey);

    /** 만료 배치용: 특정 상태이면서 입금기한이 지난 가상계좌(방치된 미입금 건). */
    List<VirtualAccount> findByStatusAndDueDateBefore(VaStatus status, Instant now, Pageable page);
}
