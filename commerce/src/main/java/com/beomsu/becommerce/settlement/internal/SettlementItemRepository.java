package com.beomsu.becommerce.settlement.internal;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

// 같은 모듈의 API(루트)가 참조하므로 public 이다. Modulith 문서가 짚듯 internal 에 있어도
// public 이면 컴파일러는 막지 못하며, 모듈 밖 접근은 ModularityTests 의 allowedDependencies 가 막는다.
public interface SettlementItemRepository extends JpaRepository<SettlementItem, Long> {

    /** 적재 멱등성: 같은 결제가 이미 정산 항목으로 쌓였는지 확인. */
    boolean existsByPaymentId(long paymentId);

    /** 에스크로 릴리스(구매확정) 반영용: orderNo로 정산 항목을 찾는다. */
    Optional<SettlementItem> findByOrderNo(String orderNo);

    /** 취소 반영용: paymentId로 정산 항목을 찾는다. */
    Optional<SettlementItem> findByPaymentId(long paymentId);

    /** 배치 집계 대상: 특정 날짜의 특정 상태(CONFIRMED) 항목들. */
    List<SettlementItem> findByStatusAndConfirmedDate(SettlementItemStatus status, LocalDate confirmedDate);

    /**
     * 배치 집계 대상: <b>그 날짜까지의 미정산 재고 전부</b>.
     *
     * <p>날짜가 정확히 일치하는 것만 모으면, 그 날짜 정산이 이미 만들어진 뒤에 늦게 도착한
     * 구매확정 이벤트가 <b>영영 집계되지 않는다</b>. 그 날짜 정산은 멱등하게 skip되고
     * 다음 날 배치는 다른 날짜만 보기 때문이다. 승인일 기준으로 집계하던 버그와 같은 실패 모드다.
     *
     * <p>그래서 <b>{@code <=}</b> 로 본다. 늦게 확정된 항목은 다음 실행이 쓸어 담는다.
     */
    List<SettlementItem> findByStatusAndConfirmedDateLessThanEqual(
            SettlementItemStatus status, LocalDate confirmedDate, Pageable page);

    /**
     * 미정산 재고가 있는 <b>판매자 목록</b> — 공정성 정책(라운드로빈)이 대상 판매자를 먼저 안다.
     *
     * <p>id 오름차순 전역 읽기는 판매자 한 곳이 틱 용량을 다 채우면 뒤 판매자를 다음 틱으로 밀어낸다.
     * 판매자별로 읽으려면 그 판매자들이 누구인지부터 알아야 한다.
     */
    @org.springframework.data.jpa.repository.Query("""
            select distinct i.sellerId from SettlementItem i
             where i.status = :status and i.confirmedDate <= :date
             order by i.sellerId
            """)
    List<Long> findSellerIdsWithPending(
            @org.springframework.data.repository.query.Param("status") SettlementItemStatus status,
            @org.springframework.data.repository.query.Param("date") LocalDate date);

    /** 판매자 한 곳의 미정산 재고 — 라운드로빈이 판매자별로 한 장씩 읽는다. id 오름차순 고정. */
    List<SettlementItem> findBySellerIdAndStatusAndConfirmedDateLessThanEqualOrderByIdAsc(
            long sellerId, SettlementItemStatus status, LocalDate confirmedDate, Pageable page);

    /**
     * 정산 한 건에 들어간 항목들.
     *
     * <p>정산이 <b>언제 번 돈</b>으로 이뤄졌는지 답하는 데 쓴다. 항목이 확정일을 들고 있어서
     * 집계일과 다른 날짜가 얼마나 섞였는지는 읽기만으로 나온다(ADR-023).
     */
    List<SettlementItem> findBySettlementId(Long settlementId);
}
