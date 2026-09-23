package com.beomsu.becommerce.fraud.model;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

// 하위 패키지가 같은 모듈 안에서 참조하므로 public 이다. 모듈 밖 접근은 ModularityTests 가 막는다.
public interface CardTransactionRepository extends JpaRepository<CardTransaction, Long> {

    /**
     * 그 카드의 최근 결제. <b>상한을 걸어 받는다.</b>
     *
     * <p>상황 2.3 에서 배치 조회 15곳에 상한을 걸었던 것과 같은 이유다. 한 카드에 수천 줄이
     * 쌓인 상태에서 전부 읽으면 그만큼 메모리에 올라간다. 그리고 창이 길어질수록 피처의
     * 뜻도 흐려진다. {@code amountToMedian} 은 <b>요즘</b> 씀씀이와 견주는 값이지 평생 평균과
     * 견주는 값이 아니다.
     *
     * <p>최신순으로 받아 뒤집는다. 오래된 순으로 받으면 상한에 걸렸을 때 <b>가장 최근 건이
     * 빠진다.</b> 그건 이번 판정에 제일 필요한 값이다.
     *
     * <p><b>{@code until} 로 뒤도 막는다.</b> 판정 시점 이후에 일어난 거래는 그때 몰랐던
     * 사실이라 창에 들어오면 미래를 보고 채점하는 셈이다. 학습에 쓰는 코퍼스는 판정 대상이
     * 늘 창의 마지막이라 이 문제가 없고, 그래서 <b>막지 않으면 학습과 서빙이 다른 창을 본다.</b>
     * 재배달이나 지연 저장으로 뒤 건이 먼저 들어와 있을 때 실제로 갈린다.
     *
     * <p>거르는 자리를 자바가 아니라 조회에 둔 이유가 하나 더 있다. 자바에서 거르면 위
     * {@code Pageable} 상한을 <b>미래 건이 먼저 먹어</b> 정작 필요한 과거 창이 짧아진다.
     */
    List<CardTransaction> findByCardKeyAndOccurredAtBetweenOrderByOccurredAtDesc(
            String cardKey, Instant since, Instant until, Pageable pageable);

    /** 이미 담은 주문인지. 아웃박스 재배달을 저장 전에 거른다. */
    boolean existsByOrderNo(String orderNo);

    /**
     * 그 주문의 결제. 유니크 제약이 있어 최대 한 줄인데 {@code List} 로 받는다.
     *
     * <p>{@code Optional} 로 받으면 어쩌다 두 줄이 생겼을 때 {@code
     * IncorrectResultSizeDataAccessException} 이 터진다. 라벨 채점은 배치라 한 건 때문에
     * 통째로 멈추면 안 된다. 상황 2.3 에서 한 건 실패가 배치를 멈추지 않게 한 것과 같다.
     */
    List<CardTransaction> findByOrderNo(String orderNo);
}
