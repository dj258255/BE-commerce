package com.beomsu.becommerce.wishlist.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 하위 패키지(web)가 같은 모듈 안에서 참조하므로 public이다. 모듈 밖 접근은 package-private이
 * 아니라 {@code ModularityTests}의 allowedDependencies가 막는다.
 */
public interface WishlistRepository extends JpaRepository<WishlistItem, Long> {

    /** 내 찜 목록 — 최근에 찜한 것이 먼저. 유니크 인덱스(userId, productId)의 선두 컬럼을 탄다. */
    List<WishlistItem> findByUserIdOrderByIdDesc(long userId);

    /**
     * 이미 찜했는지. {@code existsBy...}가 아니라 <b>행 자체</b>를 읽는다 — 멱등 추가가 "이미 있으면
     * 그때의 {@code createdAt}을 그대로 돌려주어야" 하기 때문이다(지금 시각으로 덮으면 거짓말이 된다).
     */
    Optional<WishlistItem> findByUserIdAndProductId(long userId, long productId);

    /**
     * 멱등 삭제. <b>반환값(지운 행 수)을 판정에 쓰지 않는다</b> — 없는 것을 지우는 것도 성공이다.
     * {@code long} 반환은 JPA 파생 삭제가 건수를 돌려주기 때문이다.
     */
    long deleteByUserIdAndProductId(long userId, long productId);
}
