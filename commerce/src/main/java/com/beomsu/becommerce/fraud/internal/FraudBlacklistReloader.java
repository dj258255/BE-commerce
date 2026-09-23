package com.beomsu.becommerce.fraud.internal;

import com.beomsu.becommerce.fraud.review.FraudReviewStatus;
import com.beomsu.becommerce.fraud.review.FraudReviewRepository;
import com.beomsu.becommerce.fraud.review.FraudReview;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 기동 시 FDS 카드 블랙리스트를 DB에서 재적재한다.
 *
 * <p>{@link FraudService}의 블랙리스트는 인메모리 캐시라 재시작하면 사라진다. 하지만 진실 원천은
 * REJECTED 심사 리뷰({@link FraudReview})다 — 어드민이 부정 거래로 확인한 카드는 DB에 남는다.
 * 따라서 {@link ApplicationReadyEvent} 시점에 {@code findByStatus(REJECTED)}로 되읽어 각 카드 키를
 * 다시 블랙리스트에 등록한다. 이로써 재시작 후에도 "한 번 부정으로 확인된 카드는 계속 차단된다".
 *
 * <p>fraud 모듈 내부 컴포넌트라 모듈 경계 문제는 없다.
 */
@Component
@RequiredArgsConstructor
public class FraudBlacklistReloader {

    private static final Logger log = LoggerFactory.getLogger(FraudBlacklistReloader.class);

    private final FraudReviewRepository reviewRepository;
    private final FraudService fraudService;

    /** 기동 완료 시 REJECTED 리뷰의 카드 키를 블랙리스트에 재적재한다. */
    @EventListener(ApplicationReadyEvent.class)
    public void reload() {
        List<FraudReview> rejected = reviewRepository.findByStatus(FraudReviewStatus.REJECTED);
        for (FraudReview review : rejected) {
            fraudService.blacklistCard(review.getCardKey());
        }
        log.info("FDS 블랙리스트 재적재 count={}", rejected.size());
    }
}
