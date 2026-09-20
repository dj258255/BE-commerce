package com.beomsu.becommerce.settlement.internal;

import com.beomsu.becommerce.settlement.internal.SettlementCompositionView;
import com.beomsu.becommerce.settlement.internal.SettlementItem;
import com.beomsu.becommerce.settlement.internal.SettlementItemRepository;
import com.beomsu.becommerce.settlement.internal.SettlementView;
import com.beomsu.becommerce.settlement.internal.SettlementStatus;
import com.beomsu.becommerce.settlement.internal.SettlementService;
import com.beomsu.becommerce.settlement.internal.SettlementRepository;
import com.beomsu.becommerce.settlement.internal.SettlementException;
import com.beomsu.becommerce.settlement.internal.SettlementAdminService;
import com.beomsu.becommerce.settlement.internal.Settlement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SettlementAdminServiceTest {

    private SettlementRepository repository;
    private SettlementItemRepository itemRepository;
    private SettlementService settlementService;
    private SettlementAdminService service;

    private static final LocalDate DATE = LocalDate.of(2026, 7, 5);
    private static final LocalDate PAYOUT = LocalDate.of(2026, 7, 7);
    private static final long PLATFORM = com.beomsu.becommerce.seller.SellerPayoutGate.PLATFORM_SELLER_ID;

    @BeforeEach
    void setUp() {
        repository = mock(SettlementRepository.class);
        itemRepository = mock(SettlementItemRepository.class);
        settlementService = mock(SettlementService.class);
        service = new SettlementAdminService(repository, itemRepository, settlementService,
                mock(org.springframework.context.ApplicationEventPublisher.class));
    }

    @Test
    @DisplayName("list: 정산을 페이지 뷰 record로 매핑한다")
    void listMapsToView() {
        Settlement s = Settlement.of(DATE, "KRW", 100_000, 2_700, 270, 3, PAYOUT, PLATFORM);
        Pageable pageable = PageRequest.of(0, 20);
        when(repository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(s), pageable, 1));

        Page<SettlementView> views = service.list(pageable);

        assertThat(views).hasSize(1);
        SettlementView v = views.getContent().get(0);
        assertThat(v.grossAmount()).isEqualTo(100_000);
        assertThat(v.feeAmount()).isEqualTo(2_700);
        assertThat(v.feeVatAmount()).isEqualTo(270);
        assertThat(v.netAmount()).isEqualTo(97_030);
        assertThat(v.payoutDate()).isEqualTo(PAYOUT);
        assertThat(v.status()).isEqualTo(SettlementStatus.CREATED);
        verify(repository).findAll(any(Pageable.class));
    }

    @Test
    @DisplayName("confirmPayout: CREATED → PAID_OUT 전이하고 saveAndFlush로 명시 영속")
    void confirmPayoutTransitionsAndPersists() {
        Settlement s = Settlement.of(DATE, "KRW", 100_000, 2_700, 270, 3, PAYOUT, PLATFORM);
        when(repository.findById(7L)).thenReturn(Optional.of(s));
        when(repository.saveAndFlush(s)).thenReturn(s);

        SettlementView view = service.confirmPayout(7L);

        assertThat(s.getStatus()).isEqualTo(SettlementStatus.PAID_OUT);
        assertThat(view.status()).isEqualTo(SettlementStatus.PAID_OUT);
        assertThat(view.paidOutAt()).isNotNull();
        // OSIV off — 상태 변경이 DB에 남으려면 saveAndFlush가 반드시 불려야 한다.
        verify(repository).saveAndFlush(s);
    }

    @Test
    @DisplayName("confirmPayout: 없는 id면 SETTLEMENT_NOT_FOUND 예외, saveAndFlush 안 함")
    void confirmPayoutThrowsWhenNotFound() {
        when(repository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirmPayout(404L))
                .isInstanceOf(SettlementException.class)
                .satisfies(e -> assertThat(((SettlementException) e).code())
                        .isEqualTo("SETTLEMENT_NOT_FOUND"));
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("runSettlement: settle에 위임한다")
    void runSettlementDelegates() {
        Settlement s = Settlement.of(DATE, "KRW", 100_000, 2_700, 270, 3, PAYOUT, PLATFORM);
        when(settlementService.settle(DATE)).thenReturn(s);

        Settlement result = service.runSettlement(DATE);

        assertThat(result).isSameAs(s);
        verify(settlementService).settle(DATE);
    }

    @Test
    @DisplayName("runSettlement: 재실행/대상없음이면 null 그대로 반환")
    void runSettlementReturnsNullWhenNothingCreated() {
        when(settlementService.settle(DATE)).thenReturn(null);

        assertThat(service.runSettlement(DATE)).isNull();
    }

    @Test
    @DisplayName("composition: 정산이 언제 번 돈으로 이뤄졌는지 확정일별로 가른다")
    void compositionSplitsByConfirmedDate() {
        // 8/1 정산에 7/31 확정분 7만 원과 8/1 확정분 3만 원이 섞여 있다.
        LocalDate lastDayOfJuly = LocalDate.of(2026, 7, 31);
        LocalDate firstDayOfAugust = LocalDate.of(2026, 8, 1);
        Settlement settlement = Settlement.of(firstDayOfAugust, "KRW", 100_000, 2_700, 270, 2,
                LocalDate.of(2026, 8, 4), PLATFORM);

        SettlementItem july = SettlementItem.of(31L, "order-july", 70_000, lastDayOfJuly, PLATFORM);
        july.confirm(lastDayOfJuly);
        SettlementItem august = SettlementItem.of(32L, "order-august", 30_000, firstDayOfAugust, PLATFORM);
        august.confirm(firstDayOfAugust);

        when(repository.findById(7L)).thenReturn(Optional.of(settlement));
        when(itemRepository.findBySettlementId(7L)).thenReturn(List.of(august, july));

        SettlementCompositionView view = service.composition(7L);

        assertThat(view.totalAmount()).isEqualTo(100_000L);
        assertThat(view.sameDateAmount())
                .as("그날 번 돈")
                .isEqualTo(30_000L);
        assertThat(view.carriedAmount())
                .as("며칠 전에서 넘어온 돈. 판매자가 오늘 왜 두 배냐고 물으면 이 숫자가 답한다")
                .isEqualTo(70_000L);
        assertThat(view.crossesMonth())
                .as("월 단위 리포트가 그 달 거래와 어긋나는 자리")
                .isTrue();

        // 오래된 날짜가 먼저 온다.
        assertThat(view.byConfirmedDate()).hasSize(2);
        assertThat(view.byConfirmedDate().get(0).confirmedDate()).isEqualTo(lastDayOfJuly);
        assertThat(view.byConfirmedDate().get(0).daysLate()).isEqualTo(1);
        assertThat(view.byConfirmedDate().get(1).daysLate()).isZero();
    }

    @Test
    @DisplayName("composition: 섞인 것이 없으면 넘어온 금액은 0이고 월 경계도 아니다")
    void compositionWithoutCarryOver() {
        Settlement settlement = Settlement.of(DATE, "KRW", 50_000, 1_350, 135, 1, PAYOUT, PLATFORM);
        SettlementItem sameDay = SettlementItem.of(41L, "order-same", 50_000, DATE, PLATFORM);
        sameDay.confirm(DATE);

        when(repository.findById(8L)).thenReturn(Optional.of(settlement));
        when(itemRepository.findBySettlementId(8L)).thenReturn(List.of(sameDay));

        SettlementCompositionView view = service.composition(8L);

        assertThat(view.carriedAmount()).isZero();
        assertThat(view.crossesMonth()).isFalse();
        assertThat(view.byConfirmedDate()).hasSize(1);
    }
}
