package com.beomsu.becommerce.subscription.internal;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    /** 배치 청구 대상 조회 — 지정 상태이면서 다음 청구일이 today 이하인 구독. */
    List<Subscription> findByStatusAndNextBillingDateLessThanEqual(SubscriptionStatus status, LocalDate date, Pageable page);

    /** 배치 청구 대상 조회 — ACTIVE/IN_GRACE_PERIOD 등 여러 상태를 한 번에. */
    List<Subscription> findByStatusInAndNextBillingDateLessThanEqual(
            Collection<SubscriptionStatus> statuses, LocalDate date);

    /** 내 구독 목록 — 사용자 소유 구독을 최신순으로. */
    List<Subscription> findByUserIdOrderByIdDesc(long userId);

    /**
     * 이 빌링키를 <b>아직 쓰는</b> 구독이 남았는가. 한 빌링키를 여러 구독이 공유할 수 있으므로,
     * 하나를 해지했다고 키를 폐기하면 <b>남은 구독의 결제 수단을 지우는 것</b>이 된다.
     */
    boolean existsByBillingKeyAndStatusIn(String billingKey, java.util.Collection<SubscriptionStatus> statuses);
}
