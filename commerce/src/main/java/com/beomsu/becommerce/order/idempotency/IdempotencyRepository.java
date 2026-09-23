package com.beomsu.becommerce.order.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}
