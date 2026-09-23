package com.beomsu.becommerce.subscription.billing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

// 하위 패키지가 같은 모듈 안에서 참조하므로 public 이다. 모듈 밖 접근은 package-private 이 아니라
// ModularityTests 의 allowedDependencies 가 막는다.
public interface BillingKeyRepository extends JpaRepository<BillingKey, Long> {

    // billingKey는 암호화(비결정적)라 값으로 직접 조회할 수 없다 — 결정적 블라인드 인덱스로 조회한다.
    Optional<BillingKey> findByBillingKeyIndex(String billingKeyIndex);
}
