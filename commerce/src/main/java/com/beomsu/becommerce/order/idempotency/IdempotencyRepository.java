package com.beomsu.becommerce.order.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

interface IdempotencyRepository extends JpaRepository<IdempotencyRecord, Long> {

    Optional<IdempotencyRecord> findByIdempotencyKeyAndApiPathAndHttpMethod(
            String idempotencyKey, String apiPath, String httpMethod);

    /**
     * 유효기간(15일, 토스페이먼츠 정합)이 지난 멱등 레코드를 일괄 삭제한다 — 무한 성장 방지.
     * 단일 벌크 DELETE라 엔티티를 한 건씩 지우지 않는다(그 방식은 대량에서 뒤처진다).
     * 초대량 테이블에서 락 점유를 더 줄이려면 MySQL {@code DELETE ... LIMIT}로 청크 삭제하는 확장 여지가 있다.
     */
    @Modifying
    @Query("delete from IdempotencyRecord r where r.expiresAt < :threshold")
    int deleteByExpiresAtBefore(@Param("threshold") Instant threshold);

    /**
     * 만료된 처리권을 넘겨받는다(#369). 조건부 UPDATE 라 같은 순간 여럿이 시도해도 한 요청만 1행을 얻는다.
     * 버전을 올려 두어, 처리권을 잃은 원래 요청이 뒤늦게 저장하면 버전 충돌로 막힌다.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("""
            update IdempotencyRecord r
               set r.leaseUntil = :newLeaseUntil, r.version = r.version + 1
             where r.id = :id and r.version = :version
               and r.status = :processing and r.leaseUntil < :now
            """)
    int takeOverExpiredLease(@Param("id") Long id, @Param("version") long version,
                             @Param("processing") IdempotencyRecord.Status processing,
                             @Param("now") Instant now, @Param("newLeaseUntil") Instant newLeaseUntil);
}
