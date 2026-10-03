package com.beomsu.becommerce.personalization.internal;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * {@code personalization_user_map} 조회. 밖(같은 모듈의 루트 패키지 {@code PersonalizationUserMapFacts})에서
 * 쓰므로 public 이다 — 모듈 밖 접근은 {@code ModularityTests} 의 allowedDependencies 가 막는다.
 *
 * <p>엔티티를 그대로 내보내지 않고 <b>문자열 하나만</b> 돌려준다. 소비자가 알아야 하는 것은
 * "이 회원의 H&amp;M 고객 키"뿐이고, id·매핑 표의 사정은 넘길 이유가 없다({@code RecentActivityFacts} 와 같은 결).
 */
public interface PersonalizationUserMapRepository extends JpaRepository<PersonalizationUserMap, Long> {

    @Query("select m.hmCustomerId from PersonalizationUserMap m where m.userId = :userId")
    Optional<String> findHmCustomerIdByUserId(@Param("userId") long userId);
}
