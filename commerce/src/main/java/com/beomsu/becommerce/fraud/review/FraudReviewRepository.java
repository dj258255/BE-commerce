package com.beomsu.becommerce.fraud.review;

import com.beomsu.becommerce.fraud.internal.FraudBlacklistReloader;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.Optional;
import java.util.List;

// 하위 패키지가 같은 모듈 안에서 참조하므로 public 이다. 모듈 밖 접근은 package-private 이 아니라
// ModularityTests 의 allowedDependencies 가 막는다.
public interface FraudReviewRepository extends JpaRepository<FraudReview, Long> {

    /** 상태별 심사 항목 전건 — 기동 시 블랙리스트 재적재(FraudBlacklistReloader)가 REJECTED 전건을 되읽는다. */
    List<FraudReview> findByStatus(FraudReviewStatus status);

    /**
     * 같은 주문이 이미 심사 큐에 있는가 — 아웃박스 재발행 멱등 가드.
     *
     * <p>아웃박스는 at-least-once 다. 재발행으로 같은 이벤트가 두 번 배달되면 사후 탐지가 REVIEW/BLOCK
     * 판정을 다시 내리고, 그때 큐에 같은 주문이 두 줄로 쌓인다. 이력({@code card_transactions})은
     * {@code order_no} 유니크로 막고 있었는데 정작 사람이 보는 큐는 막는 장치가 없었다.
     */
    boolean existsByOrderNo(String orderNo);

    /** 어드민 관측용 — 상태별 심사 항목 페이지(기본 PENDING = 미결 건). 전건 로딩 방지 위해 페이지 단위. */
    Page<FraudReview> findByStatus(FraudReviewStatus status, Pageable pageable);

    /**
     * 모델 점수가 높은 것부터. <b>집합은 그대로고 순서만 바뀐다.</b>
     *
     * <p>MySQL 은 {@code DESC} 정렬에서 NULL 을 뒤로 보낸다. 홀드아웃에 들었거나 채점이
     * 실패해 점수가 없는 건이 아래로 간다. 그 건들이 큐에서 빠지는 것은 아니다.
     */
    Page<FraudReview> findByStatusOrderByModelRiskDesc(FraudReviewStatus status, Pageable pageable);

    /** 상태별 건수 — 심사 큐 깊이 게이지가 쓴다. 스크레이프마다 count 한 번만 돈다. */
    long countByStatus(FraudReviewStatus status);

    /**
     * 같은 카드의 심사 이력. 심사자가 "이 카드가 전에도 걸렸나"를 보는 데 쓴다.
     *
     * <p>상한 없이 {@code List} 를 돌려주는 것이 걸리는데, 여기는 <b>카드 하나</b>로 좁힌
     * 조회라 상황 2.3 에서 상한을 건 배치 스캔과 성질이 다르다. 한 카드가 수천 번 걸릴 만큼
     * 쌓이면 그건 목록을 자를 문제가 아니라 그 카드를 이미 막았어야 하는 상황이다.
     */
    List<FraudReview> findByCardKey(String cardKey);

    /**
     * 해당 상태에서 <b>가장 오래 기다린</b> 항목의 생성 시각. 큐 깊이만으로는 적체를 못 본다.
     * 열 건이 방금 들어온 것과 한 건이 이틀 묵은 것은 위험이 다르다.
     */
    @Query("select min(r.createdAt) from FraudReview r where r.status = :status")
    Optional<Instant> findOldestCreatedAt(@Param("status") FraudReviewStatus status);

    /**
     * 규칙별 오탐 집계용 — <b>판정이 끝난</b> 심사의 근거와 결과만 읽는다.
     *
     * <p>근거가 콤마로 이어 붙인 문자열이라 SQL 로는 규칙 단위로 못 가른다. 그래서 두 칼럼만
     * 가져와 자바에서 쪼갠다. 엔티티 전건을 읽지 않는 것은 이 조회가 어드민 화면마다 도는데
     * 심사 이력은 계속 쌓이기 때문이다.
     *
     * <p>PENDING 은 애초에 안 읽는다. 분모에 넣으면 <b>심사가 밀릴수록 오탐률이 떨어져</b> 보인다.
     */
    @Query("""
            select r.reasons as reasons, r.status as status
              from FraudReview r
             where r.status in (com.beomsu.becommerce.fraud.review.FraudReviewStatus.APPROVED,
                                com.beomsu.becommerce.fraud.review.FraudReviewStatus.REJECTED)
               and r.reasons is not null
            """)
    List<JudgedReasons> findJudgedReasons();

    /** 위 조회의 투영. 근거 문자열과 사람의 판정만 있으면 규칙별로 가를 수 있다. */
    interface JudgedReasons {
        String getReasons();
        FraudReviewStatus getStatus();
    }
}
