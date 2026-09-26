package com.beomsu.becommerce.order.catalog;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

// 하위 패키지가 같은 모듈 안에서 참조하므로 public 이다. 모듈 밖 접근은 package-private 이 아니라
// ModularityTests 의 allowedDependencies 가 막는다(ProductRepository 와 같은 규칙).
public interface StockRepository extends JpaRepository<Stock, Long> {

    /** 여러 상품의 재고를 한 번에 — 목록 화면의 품절 표시용(N+1 방지). */
    List<Stock> findByProductIdIn(Collection<Long> productIds);

    /** 비관적 락 — SELECT ... FOR UPDATE. 충돌이 잦은 재고 차감에 쓴다(Phase 5 비교 실험). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Stock s where s.productId = :id")
    Optional<Stock> findByIdForUpdate(@Param("id") Long id);

    /**
     * 조건부 차감 — 락 없이 원자적 UPDATE. quantity >= qty일 때만 차감되며, 영향 행이 0이면 재고 부족.
     * 애플리케이션 락·재시도 없이 DB 한 번으로 정합성을 지키는 가장 저비용 방식.
     */
    @Modifying(clearAutomatically = true)
    @Query("update Stock s set s.quantity = s.quantity - :qty "
            + "where s.productId = :id and s.quantity >= :qty")
    int deductConditionally(@Param("id") Long id, @Param("qty") int qty);

    /** 재고 가산 — 전액 취소 시 차감했던 수량을 되돌린다. 조건 없이 원자적 UPDATE. */
    @Modifying(clearAutomatically = true)
    @Query("update Stock s set s.quantity = s.quantity + :qty where s.productId = :id")
    int restore(@Param("id") Long id, @Param("qty") int qty);

    /**
     * <b>실험용 — 재고를 0으로 만든다</b>("누군가 마지막 재고를 샀다").
     *
     * <p>차감({@code deductConditionally})과 다른 점: 차감은 수량만큼 빼지만 이쪽은 <b>남은 수량과
     * 무관하게 품절로 만든다.</b> E4 의 변화 주입은 "재고가 바뀌었다"만 필요하지 "몇 개가 팔렸다"를
     * 모델링하지 않는다. 이미 0이면 아무 것도 하지 않아 <b>같은 사실을 두 번 세지 않는다</b>
     * (영향 행 0 = 변경 없음).
     *
     * <p>{@code @Version} 을 올리지 않는다 — 이 id 들은 상점·주문 경로에 없고(products 행이 없다)
     * 낙관적 락 실험의 대상도 아니다. 대신 품절 여부는 quantity 로만 판정한다.
     */
    @Modifying(clearAutomatically = true)
    @Query("update Stock s set s.quantity = 0 where s.productId = :id and s.quantity > 0")
    int sellOut(@Param("id") Long id);

    /** 실험용 — 재고를 지정 수량으로 되돌린다("입고됐다"). 영향 행 0이면 그 id 의 재고 행이 없다. */
    @Modifying(clearAutomatically = true)
    @Query("update Stock s set s.quantity = :qty where s.productId = :id")
    int setQuantity(@Param("id") Long id, @Param("qty") int qty);

    /**
     * 실험용 — <b>품절일 때만</b> 입고한다(조건부).
     *
     * <p>{@link #setQuantity} 와 나뉘어 있는 이유가 <b>동시성</b>이다. 변화 주입은 VU 여러 개가
     * 동시에 돌기 때문에 두 스레드가 같은 항목을 입고하려 할 수 있다. 무조건 UPDATE면 둘 다 "성공"해
     * (MySQL 은 매칭 행 수를 돌려준다) <b>품절 집합 크기가 한 칸 늘어난다</b> — 그러면 ①의 통제가 깨진다.
     * 조건부로 만들면 <b>실제로 상태를 바꾼 쪽만 성공</b>하므로 크기가 흔들리지 않는다.
     *
     * <p>영향 행 0 = "이미 팔 수 있었다"이고, 그것은 <b>변경이 아니다</b>(version 을 올리지 않는다).
     */
    @Modifying(clearAutomatically = true)
    @Query("update Stock s set s.quantity = :qty where s.productId = :id and s.quantity <= 0")
    int setQuantityIfSoldOut(@Param("id") Long id, @Param("qty") int qty);

    /**
     * <b>실험용(X3, #317) — 품절(quantity = 0) 상품 id.</b>
     *
     * <p>{@code PRE} 재고 확인 방식이 모델 호출 <b>전에</b> 후보에서 빼려고 쓴다. 요청마다 이 질의를
     * 날리므로 결과 수를 {@code Pageable} 로 자른다 — "품절 상품이 적다"는 가정의 상한이다.
     * 전체 품절 목록을 캐시하는 대안은 재고 변경을 통보받아 무효화해야 하고(무효화 지점이 하나 더
     * 생긴다) 그 대가는 여기서 지지 않는다.
     */
    @Query("select s.productId from Stock s where s.quantity = 0 order by s.productId")
    List<Long> findSoldOutIds(Pageable pageable);
}
