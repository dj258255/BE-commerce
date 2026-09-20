package com.beomsu.becommerce.point.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// 같은 모듈의 API(루트)가 참조하므로 public 이다. Modulith 문서가 짚듯 internal 에 있어도
// public 이면 컴파일러는 막지 못하며, 모듈 밖 접근은 ModularityTests 의 allowedDependencies 가 막는다.
public interface PointAccountRepository extends JpaRepository<PointAccount, Long> {

    /**
     * 적립 전용 원자 증가 — 읽고-고치고-쓰는 낙관적 락 경로를 우회한다.
     *
     * <p>같은 계정의 동시 적립이 {@code @Version} 충돌로 결제까지 실패시키는 것을 부하 실험으로
     * 확인했다(30VU 단일 계정, 승인 성공률 39.6%). 적립(+N)은 교환법칙이 성립하는 연산이라
     * 읽을 필요 없이 DB에서 원자적으로 더하면 경합 자체가 사라진다 — 재고 차감의 조건부
     * UPDATE(ADR-004)와 같은 처방이다. 계좌 미존재 레이스는 upsert 한 문장으로 흡수한다.
     *
     * <p><b>version도 함께 올리는 이유</b>: 올리지 않으면 동시에 엔티티로 로드한 다른
     * 경로(사용·환불)가 낡은 balance로 덮어써도 버전 검사를 통과해 적립분이 소리 없이
     * 사라진다(lost update). version을 올려두면 그 경로는 기존처럼 낙관적 락 충돌로 감지된다.
     *
     * <p>상세 분석·실측: docs/performance/point-accrual-contention.md
     */
    @Modifying
    @Query(value = """
            INSERT INTO point_accounts (user_id, balance, version) VALUES (:userId, :amount, 0)
            ON DUPLICATE KEY UPDATE balance = balance + :amount, version = version + 1
            """, nativeQuery = true)
    void increaseBalanceAtomically(@Param("userId") long userId, @Param("amount") long amount);
}
