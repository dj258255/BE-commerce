package com.beomsu.becommerce.subscription.dunning;

import com.beomsu.becommerce.subscription.billing.BillingResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

// 하위 패키지가 같은 모듈 안에서 참조하므로 public 이다. 모듈 밖 접근은 package-private 이 아니라
// ModularityTests 의 allowedDependencies 가 막는다.
public interface DunningAttemptRepository extends JpaRepository<DunningAttempt, Long> {

    /** 재시도 소진 판정·attemptNo 계산용: 해당 구독의 특정 결과(주로 SOFT_DECLINE) 시도 횟수. */
    int countBySubscriptionIdAndResult(Long subscriptionId, BillingResult result);

    /** 청구 이력 조회 — 구독의 청구 시도를 순서대로(시도번호 오름차순). */
    List<DunningAttempt> findBySubscriptionIdOrderByIdAsc(Long subscriptionId);
}
