package com.beomsu.becommerce.personalization.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 하위 패키지(web)가 같은 모듈 안에서 참조하므로 public이다. 모듈 밖 접근은 package-private이
 * 아니라 {@code ModularityTests}의 allowedDependencies가 막는다.
 */
public interface UserActivityRepository extends JpaRepository<UserActivity, Long> {

    /** 컨텍스트가 가리키는 순번까지 실제로 들어왔는지 확인할 때 쓴다(E2의 대조에도 쓸 수 있다). */
    List<UserActivity> findTop50ByUserIdOrderBySeqDesc(long userId);
}
