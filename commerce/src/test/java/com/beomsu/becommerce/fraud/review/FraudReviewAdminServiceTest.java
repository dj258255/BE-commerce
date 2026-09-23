package com.beomsu.becommerce.fraud.review;

import com.beomsu.becommerce.fraud.internal.FraudService;
import com.beomsu.becommerce.fraud.internal.FraudResult;
import com.beomsu.becommerce.fraud.internal.FraudException;
import com.beomsu.becommerce.fraud.internal.FdsDecision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class FraudReviewAdminServiceTest {

    private FraudReviewRepository repository;
    private FraudService fraudService;
    private FraudReviewAdminService service;

    @BeforeEach
    void setUp() {
        repository = mock(FraudReviewRepository.class);
        fraudService = mock(FraudService.class);
        service = new FraudReviewAdminService(repository, fraudService);
    }

    private FraudReview flagged() {
        FraudReview review = FraudReview.flagged("ord-1", 10L, "card-key-xyz", 50_000,
                new FraudResult(70, FdsDecision.REVIEW, List.of("HIGH_AMOUNT")));
        // 영속 엔티티는 id가 채워진다 — 뷰가 long id를 언박싱하므로 테스트에서도 id를 채운다.
        ReflectionTestUtils.setField(review, "id", 1L);
        return review;
    }

    @Test
    @DisplayName("list(PENDING): 상태로 페이지 조회해 뷰로 매핑(카드 키는 마스킹)")
    void listMapsViews() {
        Pageable pageable = PageRequest.of(0, 20);
        when(repository.findByStatus(eq(FraudReviewStatus.PENDING), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(flagged()), pageable, 1));

        Page<FraudReviewView> views = service.list(FraudReviewStatus.PENDING, pageable);

        assertThat(views).hasSize(1);
        assertThat(views.getContent().get(0).orderNo()).isEqualTo("ord-1");
        assertThat(views.getContent().get(0).status()).isEqualTo("PENDING");
        assertThat(views.getContent().get(0).cardKey()).isEqualTo("card****-xyz"); // 앞4·뒤4 마스킹
    }

    @Test
    @DisplayName("approve: APPROVED로 전이하고 saveAndFlush로 영속")
    void approveTransitionsAndFlushes() {
        FraudReview review = flagged();
        when(repository.findById(1L)).thenReturn(Optional.of(review));

        FraudReviewView view = service.approve(1L, "admin");

        assertThat(review.getStatus()).isEqualTo(FraudReviewStatus.APPROVED);
        assertThat(view.status()).isEqualTo("APPROVED");
        assertThat(view.reviewedBy()).isEqualTo("admin");
        verify(repository).saveAndFlush(review);
        // 승인은 블랙리스트 등록을 유발하지 않는다(정상 거래로 확인).
        verify(fraudService, never()).blacklistCard(anyString());
    }

    @Test
    @DisplayName("reject: REJECTED로 전이 + saveAndFlush + 카드 블랙리스트 등록")
    void rejectTransitionsBlacklistsAndFlushes() {
        FraudReview review = flagged();
        when(repository.findById(1L)).thenReturn(Optional.of(review));

        FraudReviewView view = service.reject(1L, "admin");

        assertThat(review.getStatus()).isEqualTo(FraudReviewStatus.REJECTED);
        assertThat(view.status()).isEqualTo("REJECTED");
        verify(fraudService).blacklistCard("card-key-xyz"); // 원본 키(마스킹 아님)로 등록
        verify(repository).saveAndFlush(review);
    }

    @Test
    @DisplayName("approve 가드: PENDING이 아니면 INVALID_FRAUD_REVIEW_STATE")
    void approveGuardsNonPending() {
        FraudReview review = flagged();
        review.reject("admin"); // 이미 종결
        when(repository.findById(1L)).thenReturn(Optional.of(review));

        assertThatThrownBy(() -> service.approve(1L, "admin2"))
                .isInstanceOf(FraudException.class)
                .satisfies(e -> assertThat(((FraudException) e).code())
                        .isEqualTo("INVALID_FRAUD_REVIEW_STATE"));

        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("reject 가드: PENDING이 아니면 블랙리스트 등록·저장 없이 예외")
    void rejectGuardsNonPending() {
        FraudReview review = flagged();
        review.approve("admin"); // 이미 종결
        when(repository.findById(1L)).thenReturn(Optional.of(review));

        assertThatThrownBy(() -> service.reject(1L, "admin2"))
                .isInstanceOf(FraudException.class)
                .satisfies(e -> assertThat(((FraudException) e).code())
                        .isEqualTo("INVALID_FRAUD_REVIEW_STATE"));

        verify(fraudService, never()).blacklistCard(anyString());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("없는 id: FRAUD_REVIEW_NOT_FOUND")
    void notFound() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.approve(99L, "admin"))
                .isInstanceOf(FraudException.class)
                .satisfies(e -> assertThat(((FraudException) e).code())
                        .isEqualTo("FRAUD_REVIEW_NOT_FOUND"));
    }
}
