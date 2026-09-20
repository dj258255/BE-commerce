package com.beomsu.becommerce.fraud.internal;

import com.beomsu.becommerce.fraud.internal.FraudService;
import com.beomsu.becommerce.fraud.internal.FraudResult;
import com.beomsu.becommerce.fraud.internal.FraudBlacklistReloader;
import com.beomsu.becommerce.fraud.internal.FdsDecision;
import com.beomsu.becommerce.fraud.review.FraudReviewStatus;
import com.beomsu.becommerce.fraud.review.FraudReviewRepository;
import com.beomsu.becommerce.fraud.review.FraudReview;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 기동 재적재 단위 테스트 — REJECTED 리뷰의 카드 키를 블랙리스트에 다시 등록하는지 검증한다.
 */
class FraudBlacklistReloaderTest {

    private FraudReview rejected(String cardKey) {
        FraudReview review = FraudReview.flagged("ord-1", 10L, cardKey, 50_000,
                new FraudResult(100, FdsDecision.BLOCK, List.of("BLACKLISTED_CARD")));
        review.reject("admin"); // PENDING → REJECTED
        return review;
    }

    @Test
    @DisplayName("REJECTED 리뷰의 카드 키를 각각 블랙리스트에 재적재한다")
    void reloadsRejectedCards() {
        FraudReviewRepository repository = mock(FraudReviewRepository.class);
        FraudService fraudService = mock(FraudService.class);
        when(repository.findByStatus(FraudReviewStatus.REJECTED))
                .thenReturn(List.of(rejected("card-A"), rejected("card-B")));

        new FraudBlacklistReloader(repository, fraudService).reload();

        verify(fraudService).blacklistCard("card-A");
        verify(fraudService).blacklistCard("card-B");
    }

    @Test
    @DisplayName("REJECTED 리뷰가 없으면 블랙리스트 등록도 없다")
    void noRejectedNoBlacklist() {
        FraudReviewRepository repository = mock(FraudReviewRepository.class);
        FraudService fraudService = mock(FraudService.class);
        when(repository.findByStatus(FraudReviewStatus.REJECTED)).thenReturn(List.of());

        new FraudBlacklistReloader(repository, fraudService).reload();

        verify(fraudService, never()).blacklistCard(org.mockito.ArgumentMatchers.anyString());
    }
}
