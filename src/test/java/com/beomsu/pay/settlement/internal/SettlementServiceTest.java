package com.beomsu.pay.settlement.internal;

import static org.mockito.ArgumentMatchers.eq;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;

import org.springframework.data.domain.Pageable;

import com.beomsu.pay.settlement.internal.SettlementService;
import com.beomsu.pay.settlement.internal.SettlementRepository;
import com.beomsu.pay.settlement.internal.SettlementItemStatus;
import com.beomsu.pay.settlement.internal.SettlementItemRepository;
import com.beomsu.pay.settlement.internal.SettlementItem;
import com.beomsu.pay.settlement.internal.SettlementAdjustmentRepository;
import com.beomsu.pay.settlement.internal.Settlement;
import com.beomsu.pay.settlement.internal.BusinessDays;
import com.beomsu.pay.payment.PaymentCanceledEvent;
import com.beomsu.pay.payment.PaymentConfirmedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SettlementServiceTest {

    private SettlementItemRepository itemRepository;
    private SettlementRepository settlementRepository;
    private SettlementAdjustmentRepository adjustmentRepository;
    private MeterRegistry meterRegistry;
    private SettlementService service;
    /** 판매자 지급 게이트는 기본 통과. 전환 스위치를 켠 인스턴스를 따로 만들 때도 쓴다. */
    private com.beomsu.pay.seller.SellerPayoutGate alwaysAllow;

    private static final Instant APPROVED_AT = Instant.parse("2026-07-05T09:00:00Z");
    private static final LocalDate DATE =
            LocalDate.ofInstant(APPROVED_AT, SettlementService.SETTLEMENT_ZONE);
    private static final long PLATFORM = com.beomsu.pay.seller.SellerPayoutGate.PLATFORM_SELLER_ID;

    @BeforeEach
    void setUp() {
        itemRepository = mock(SettlementItemRepository.class);
        settlementRepository = mock(SettlementRepository.class);
        adjustmentRepository = mock(SettlementAdjustmentRepository.class);
        meterRegistry = new SimpleMeterRegistry();
        // feeBps=270(2.7%), payoutDays=2 — application.yml 기본값과 동일하게 주입.
        // 판매자 지급 게이트는 기본 통과로 둔다. 이 테스트가 보는 것은 집계와 금액이지
        // 심사가 아니다. 심사가 막는 경로는 SettlementPayoutHoldTest 가 따로 본다.
        alwaysAllow = new com.beomsu.pay.seller.SellerPayoutGate(null) {
            @Override
            public Decision check(long sellerId) {
                return new Decision(true, "테스트: 심사 통과로 둔다");
            }
        };
        service = new SettlementService(itemRepository, settlementRepository,
                adjustmentRepository, meterRegistry, alwaysAllow, 270L, 2, false);
    }

    /** CONFIRMED 상태의 항목을 만든다(승인·구매확정이 같은 날 DATE인 경우 — confirmedDate=DATE). */
    private static SettlementItem confirmedItem(long paymentId, String orderNo, long amount) {
        SettlementItem item = SettlementItem.of(paymentId, orderNo, amount, DATE, PLATFORM);
        item.confirm(DATE);
        return item;
    }

    @Test
    @DisplayName("결제 승인: 정산 항목을 PENDING_CONFIRMATION(구매확정 대기)으로 적재한다")
    void registersConfirmedPaymentAsItem() {
        when(itemRepository.existsByPaymentId(100L)).thenReturn(false);
        PaymentConfirmedEvent event = new PaymentConfirmedEvent("order-1", 100L, 10_000, APPROVED_AT);

        service.registerConfirmedPayment(event);

        ArgumentCaptor<SettlementItem> captor = ArgumentCaptor.forClass(SettlementItem.class);
        verify(itemRepository).save(captor.capture());
        SettlementItem item = captor.getValue();
        assertThat(item.getPaymentId()).isEqualTo(100L);
        assertThat(item.getOrderNo()).isEqualTo("order-1");
        assertThat(item.getAmount()).isEqualTo(10_000);
        assertThat(item.getConfirmedDate()).isEqualTo(DATE);
        assertThat(item.getStatus()).isEqualTo(SettlementItemStatus.PENDING_CONFIRMATION);
    }

    @Test
    @DisplayName("새벽 승인 건도 KST 날짜로 스탬프된다 — UTC로 잡으면 전날로 밀려 영구 미정산이 된다")
    void stampsSettlementDateInKstNotUtc() {
        // KST 2026-07-05 08:00 = UTC 2026-07-04 23:00. UTC로 스탬프하면 7/4로 밀린다.
        Instant dawnKst = Instant.parse("2026-07-04T23:00:00Z");
        when(itemRepository.existsByPaymentId(200L)).thenReturn(false);

        service.registerConfirmedPayment(new PaymentConfirmedEvent("order-dawn", 200L, 10_000, dawnKst));

        ArgumentCaptor<SettlementItem> captor = ArgumentCaptor.forClass(SettlementItem.class);
        verify(itemRepository).save(captor.capture());
        assertThat(captor.getValue().getConfirmedDate())
                .as("KST 영업일 기준이어야 한다 — 7/4로 밀리면 7/4 배치는 이미 지나가 어느 배치에도 안 잡힌다")
                .isEqualTo(LocalDate.of(2026, 7, 5));
    }

    @Test
    @DisplayName("같은 결제가 두 번 와도 정산 항목은 한 번만 적재 (멱등)")
    void idempotentOnDuplicatePayment() {
        when(itemRepository.existsByPaymentId(100L)).thenReturn(true);
        PaymentConfirmedEvent event = new PaymentConfirmedEvent("order-1", 100L, 10_000, APPROVED_AT);

        service.registerConfirmedPayment(event);

        verify(itemRepository, never()).save(any());
    }

    @Test
    @DisplayName("에스크로 릴리스: 항목을 CONFIRMED로 전이하고 confirmedDate를 릴리스일로 재스탬프 후 saveAndFlush")
    void confirmSettlementTransitionsAndRestampsDate() {
        // 승인일(DATE)에 적재된 항목이 며칠 뒤 릴리스된다 — 실제 에스크로 홀드 경로.
        SettlementItem item = SettlementItem.of(1L, "order-1", 10_000, DATE, PLATFORM);
        LocalDate releaseDate = DATE.plusDays(7); // 기본 7일 홀드 후 릴리스
        when(itemRepository.findByOrderNo("order-1")).thenReturn(Optional.of(item));

        service.confirmSettlement("order-1", releaseDate);

        assertThat(item.getStatus()).isEqualTo(SettlementItemStatus.CONFIRMED);
        // 핵심: 집계 기준일이 승인일(DATE)이 아니라 릴리스일로 재스탬프돼야 한다.
        assertThat(item.getConfirmedDate()).isEqualTo(releaseDate);
        verify(itemRepository).saveAndFlush(item);
    }

    @Test
    @DisplayName("지연 구매확정 회귀: 승인일 배치는 이 항목을 못 잡고, 릴리스일 배치가 잡는다(영구 미정산 방지)")
    void delayedConfirmationIsSettledOnReleaseDateNotApprovalDate() {
        // 승인일 D에 적재(PENDING) → D+7 릴리스로 CONFIRMED. confirmedDate는 D+7로 재스탬프돼야 한다.
        SettlementItem item = SettlementItem.of(1L, "order-1", 10_000, DATE, PLATFORM);
        LocalDate releaseDate = DATE.plusDays(7);
        when(itemRepository.findByOrderNo("order-1")).thenReturn(Optional.of(item));
        service.confirmSettlement("order-1", releaseDate);

        // 릴리스일 배치가 이 항목을 집계 대상으로 조회한다(승인일이 아니라).
        when(settlementRepository.existsBySettlementDateAndCurrency(releaseDate, "KRW")).thenReturn(false);
        when(itemRepository.findByStatusAndConfirmedDateLessThanEqual( eq(SettlementItemStatus.CONFIRMED), eq(releaseDate), any(Pageable.class)))
                .thenReturn(List.of(item));
        when(settlementRepository.save(any(Settlement.class))).thenAnswer(inv -> inv.getArgument(0));

        Settlement settled = service.settle(releaseDate);

        assertThat(settled).isNotNull();
        assertThat(settled.getSettlementDate()).isEqualTo(releaseDate);
        assertThat(item.getStatus()).isEqualTo(SettlementItemStatus.SETTLED);
        // 승인일(DATE) 배치엔 이 항목이 confirmedDate로 걸리지 않는다(재스탬프됐으므로).
        assertThat(item.getConfirmedDate()).isNotEqualTo(DATE);
    }

    @Test
    @DisplayName("당일 집계가 끝난 뒤 확정된 항목을 다음 날 집계가 줍는다 — 조회를 그 날짜 이하로 넓힌 이유")
    void lateConfirmationIsSweptByTheNextDayRun() {
        // D 집계가 이미 끝났다. 그 뒤에 D 로 확정된 항목이 하나 더 들어온다.
        SettlementItem late = SettlementItem.of(9L, "order-late", 50_000, DATE, PLATFORM);
        late.confirm(DATE);

        // D 는 이미 정산이 있어 재실행이 건너뛴다. 여기서 끝나면 이 항목은 영구 미정산이다.
        when(settlementRepository.existsBySettlementDateAndCurrencyAndSellerId(eq(DATE), eq("KRW"), anyLong())).thenReturn(true);
        assertThat(service.settle(DATE))
                .as("이미 정산된 날짜는 재실행이 건너뛴다. 그래서 늦게 확정된 건이 여기서 안 잡힌다")
                .isNull();

        // D+1 집계. 조회가 <그 날짜 이하>라 confirmedDate=D 인 이 항목이 걸린다.
        LocalDate nextDay = DATE.plusDays(1);
        when(settlementRepository.existsBySettlementDateAndCurrencyAndSellerId(eq(nextDay), eq("KRW"), anyLong())).thenReturn(false);
        when(itemRepository.findByStatusAndConfirmedDateLessThanEqual(
                eq(SettlementItemStatus.CONFIRMED), eq(nextDay), any(Pageable.class)))
                .thenReturn(List.of(late));
        when(settlementRepository.save(any(Settlement.class))).thenAnswer(inv -> inv.getArgument(0));

        Settlement swept = service.settle(nextDay);

        assertThat(swept)
                .as("날짜가 정확히 맞는 것만 모으면 이 항목이 또 영영 빠진다")
                .isNotNull();
        assertThat(swept.getSettlementDate()).isEqualTo(nextDay);
        assertThat(swept.getGrossAmount()).isEqualTo(50_000L);
        assertThat(late.getStatus()).isEqualTo(SettlementItemStatus.SETTLED);
    }

    @Test
    @DisplayName("쓸어 담은 정산은 월 경계를 넘는다 — 7월 확정분이 8월 정산에 섞이고, 얼마가 섞였는지는 어디에도 안 남는다")
    void sweptSettlementCrossesMonthBoundary() {
        // 7/31 에 확정됐지만 그날 집계에서 빠진 항목. 다음 집계는 8/1 이다.
        LocalDate lastDayOfJuly = LocalDate.of(2026, 7, 31);
        LocalDate firstDayOfAugust = LocalDate.of(2026, 8, 1);

        SettlementItem july = SettlementItem.of(21L, "order-july", 70_000, lastDayOfJuly, PLATFORM);
        july.confirm(lastDayOfJuly);
        SettlementItem august = SettlementItem.of(22L, "order-august", 30_000, firstDayOfAugust, PLATFORM);
        august.confirm(firstDayOfAugust);

        when(settlementRepository.existsBySettlementDateAndCurrencyAndSellerId(
                eq(firstDayOfAugust), eq("KRW"), anyLong())).thenReturn(false);
        when(itemRepository.findByStatusAndConfirmedDateLessThanEqual(
                eq(SettlementItemStatus.CONFIRMED), eq(firstDayOfAugust), any(Pageable.class)))
                .thenReturn(List.of(july, august));
        when(settlementRepository.save(any(Settlement.class))).thenAnswer(inv -> inv.getArgument(0));

        Settlement settlement = service.settle(firstDayOfAugust);

        // 7월 거래가 8월 정산에 들어간다. 누락을 막는 설계의 의도된 결과다.
        assertThat(settlement.getSettlementDate()).isEqualTo(firstDayOfAugust);
        assertThat(settlement.getGrossAmount()).isEqualTo(100_000L);
        assertThat(settlement.getItemCount()).isEqualTo(2);

        // 여기가 ADR-023 이 다루는 자리다. 정산 한 건이 <언제 번 돈인지>를 답하지 못한다.
        // 항목에는 확정일이 남아 있지만 정산에는 집계일 하나뿐이라, 8월 정산의 10만 원 중
        // 7만 원이 7월분이라는 사실이 정산 레코드만 봐서는 복구되지 않는다.
        assertThat(july.getConfirmedDate()).isEqualTo(lastDayOfJuly);
        assertThat(august.getConfirmedDate()).isEqualTo(firstDayOfAugust);
        assertThat(settlement.getSettlementDate().getMonth())
                .as("월 단위로 묶은 리포트는 그 달 거래와 일치하지 않는다")
                .isNotEqualTo(july.getConfirmedDate().getMonth());
    }

    @Test
    @DisplayName("항목 없는 구매확정은 이제 세어진다 — 전에는 몇 번 일어났는지조차 안 남았다")
    void missingItemIsCounted() {
        when(itemRepository.findByOrderNo("order-x")).thenReturn(Optional.empty());

        service.confirmSettlement("order-x", DATE);

        assertThat(meterRegistry.counter("settlement.confirm.missing_item").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("전환 기간 스위치를 켜면 항목 없는 구매확정이 예외가 된다 — 조용한 지급 누락을 막는다")
    void missingItemIsIncidentWhenSwitchedOn() {
        // 서비스를 분리해 새 저장소로 옮기면, 전환 전 승인된 주문의 항목은 새 쪽에 없다.
        // 재전달해도 생기지 않으므로 이건 레이스가 아니라 사고다(ADR-024).
        SettlementService strict = new SettlementService(itemRepository, settlementRepository,
                adjustmentRepository, meterRegistry, alwaysAllow, 270L, 2, true);
        when(itemRepository.findByOrderNo("order-cutover")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> strict.confirmSettlement("order-cutover", DATE))
                .isInstanceOf(SettlementException.class)
                .hasMessageContaining("전환 기간");

        verify(itemRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("에스크로 릴리스: 항목이 없으면(순서 레이스) 무시하고 저장하지 않는다")
    void confirmSettlementMissingItemIsIgnored() {
        when(itemRepository.findByOrderNo("order-x")).thenReturn(Optional.empty());

        service.confirmSettlement("order-x", DATE);

        verify(itemRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("정산 배치: CONFIRMED만 합계 → 2.7% 수수료+부가세(내림) → net 저장 + 항목 SETTLED")
    void settleAggregatesFeeAndMarksItems() {
        when(settlementRepository.existsBySettlementDateAndCurrencyAndSellerId(eq(DATE), eq("KRW"), anyLong())).thenReturn(false);
        SettlementItem item1 = confirmedItem(1L, "order-1", 40_000);
        SettlementItem item2 = confirmedItem(2L, "order-2", 60_000);
        when(itemRepository.findByStatusAndConfirmedDateLessThanEqual( eq(SettlementItemStatus.CONFIRMED), eq(DATE), any(Pageable.class)))
                .thenReturn(List.of(item1, item2));
        when(settlementRepository.save(any(Settlement.class))).thenAnswer(inv -> inv.getArgument(0));

        Settlement settlement = service.settle(DATE);

        ArgumentCaptor<Settlement> captor = ArgumentCaptor.forClass(Settlement.class);
        verify(settlementRepository).save(captor.capture());
        Settlement saved = captor.getValue();
        // 검산: gross=100,000, feeBps=270 → fee=2700, feeVat=270, net=97030
        assertThat(saved.getGrossAmount()).isEqualTo(100_000);  // 40,000 + 60,000
        assertThat(saved.getFeeAmount()).isEqualTo(2_700);      // 100,000 * 270 / 10000
        assertThat(saved.getFeeVatAmount()).isEqualTo(270);     // fee / 10
        assertThat(saved.getNetAmount()).isEqualTo(97_030);     // gross - fee - feeVat
        assertThat(saved.getItemCount()).isEqualTo(2);
        assertThat(saved.getSettlementDate()).isEqualTo(DATE);
        // 지급예정일 = 정산일 + 2영업일. DATE(2026-07-05, 일요일) → 화(07-07)
        assertThat(saved.getPayoutDate()).isEqualTo(BusinessDays.plusBusinessDays(DATE, 2));
        assertThat(settlement).isSameAs(saved);

        // 집계된 항목은 SETTLED로 전이
        assertThat(item1.getStatus()).isEqualTo(SettlementItemStatus.SETTLED);
        assertThat(item2.getStatus()).isEqualTo(SettlementItemStatus.SETTLED);
    }

    @Test
    @DisplayName("수수료 검산: gross=100,000 → fee 2700, feeVat 270, net 97030")
    void settleFeeModelExactValues() {
        when(settlementRepository.existsBySettlementDateAndCurrencyAndSellerId(eq(DATE), eq("KRW"), anyLong())).thenReturn(false);
        SettlementItem item = confirmedItem(1L, "order-1", 100_000);
        when(itemRepository.findByStatusAndConfirmedDateLessThanEqual( eq(SettlementItemStatus.CONFIRMED), eq(DATE), any(Pageable.class)))
                .thenReturn(List.of(item));
        when(settlementRepository.save(any(Settlement.class))).thenAnswer(inv -> inv.getArgument(0));

        Settlement saved = service.settle(DATE);

        assertThat(saved.getGrossAmount()).isEqualTo(100_000);
        assertThat(saved.getFeeAmount()).isEqualTo(2_700);
        assertThat(saved.getFeeVatAmount()).isEqualTo(270);
        assertThat(saved.getNetAmount()).isEqualTo(97_030);
    }

    @Test
    @DisplayName("정산 배치는 CONFIRMED만 조회한다 — PENDING_CONFIRMATION(구매확정 전)은 집계 제외")
    void settleQueriesOnlyConfirmed() {
        when(settlementRepository.existsBySettlementDateAndCurrencyAndSellerId(eq(DATE), eq("KRW"), anyLong())).thenReturn(false);
        SettlementItem confirmed = confirmedItem(1L, "order-1", 10_000);
        when(itemRepository.findByStatusAndConfirmedDateLessThanEqual( eq(SettlementItemStatus.CONFIRMED), eq(DATE), any(Pageable.class)))
                .thenReturn(List.of(confirmed));
        when(settlementRepository.save(any(Settlement.class))).thenAnswer(inv -> inv.getArgument(0));

        service.settle(DATE);

        // 배치는 CONFIRMED 상태만 조회 대상으로 삼는다(보류 실현)
        verify(itemRepository).findByStatusAndConfirmedDateLessThanEqual( eq(SettlementItemStatus.CONFIRMED), eq(DATE), any(Pageable.class));
        verify(itemRepository, never())
                .findByStatusAndConfirmedDateLessThanEqual(eq(SettlementItemStatus.PENDING_CONFIRMATION), any(), any(Pageable.class));
    }

    @Test
    @DisplayName("이미 정산된 날짜 재실행: 정산을 새로 만들지 않는다 (배치 멱등)")
    void idempotentOnAlreadySettledDate() {
        // 판매자별 멱등으로 바뀌면서 순서가 바뀌었다. 어떤 판매자에게 미정산 항목이 있는지
        // 알아야 그 판매자의 정산이 이미 있는지 볼 수 있으므로, 항목 조회가 먼저다.
        // 전에는 날짜만 보고 조회 없이 건너뛰었는데, 그러면 <b>나중에 등록된 판매자의 정산이
        // 영영 안 나간다.</b> 재실행 때 조회 한 번이 더 도는 것이 그 대가다.
        when(itemRepository.findByStatusAndConfirmedDateLessThanEqual( eq(SettlementItemStatus.CONFIRMED), eq(DATE), any(Pageable.class)))
                .thenReturn(List.of(confirmedItem(1L, "ORD-1", 10_000L)));
        when(settlementRepository.existsBySettlementDateAndCurrencyAndSellerId(DATE, "KRW", PLATFORM)).thenReturn(true);

        Settlement settlement = service.settle(DATE);

        assertThat(settlement).isNull();
        verify(settlementRepository, never()).save(any());
    }

    @Test
    @DisplayName("판매자가 다르면 정산이 따로 난다 — 한쪽이 이미 정산돼도 다른 쪽은 나간다")
    void settlesPerSellerIndependently() {
        SettlementItem platform = confirmedItem(1L, "ORD-1", 10_000L);     // 플랫폼 직판
        SettlementItem bySeller = SettlementItem.of(2L, "ORD-2", 20_000L, DATE, 7L);
        bySeller.confirm(DATE);
        when(itemRepository.findByStatusAndConfirmedDateLessThanEqual( eq(SettlementItemStatus.CONFIRMED), eq(DATE), any(Pageable.class)))
                .thenReturn(List.of(platform, bySeller));
        // 플랫폼 직판은 이미 정산됐고, 판매자 7 은 아직이다
        when(settlementRepository.existsBySettlementDateAndCurrencyAndSellerId(DATE, "KRW", PLATFORM)).thenReturn(true);
        when(settlementRepository.existsBySettlementDateAndCurrencyAndSellerId(DATE, "KRW", 7L)).thenReturn(false);
        when(settlementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.settle(DATE);

        // 판매자 7 것만 저장된다. 날짜만 보고 건너뛰면 이게 안 나간다.
        verify(settlementRepository).save(argThat(x -> x.getGrossAmount() == 20_000L));
    }

    @Test
    @DisplayName("집계 대상 CONFIRMED 항목이 없으면 빈 정산을 만들지 않는다")
    void noItemsProducesNoSettlement() {
        when(settlementRepository.existsBySettlementDateAndCurrencyAndSellerId(eq(DATE), eq("KRW"), anyLong())).thenReturn(false);
        when(itemRepository.findByStatusAndConfirmedDateLessThanEqual( eq(SettlementItemStatus.CONFIRMED), eq(DATE), any(Pageable.class)))
                .thenReturn(List.of());

        Settlement settlement = service.settle(DATE);

        assertThat(settlement).isNull();
        verify(settlementRepository, never()).save(any());
    }

    @Test
    @DisplayName("전액취소: 항목을 CANCELED로 제외하고 saveAndFlush (잔액 0)")
    void reflectCancellationFullyCancels() {
        SettlementItem item = confirmedItem(100L, "order-1", 10_000);
        when(itemRepository.findByPaymentId(100L)).thenReturn(Optional.of(item));
        PaymentCanceledEvent event = new PaymentCanceledEvent("order-1", 100L, 1, 10_000, 0, true, java.time.Instant.now());

        service.reflectCancellation(event);

        assertThat(item.getStatus()).isEqualTo(SettlementItemStatus.CANCELED);
        verify(itemRepository).saveAndFlush(item);
    }

    @Test
    @DisplayName("부분취소: 정산 금액을 취소 후 잔액(절대값)으로 세팅하고 상태 유지, saveAndFlush")
    void reflectCancellationSetsSettleableBalance() {
        SettlementItem item = confirmedItem(100L, "order-1", 10_000);
        when(itemRepository.findByPaymentId(100L)).thenReturn(Optional.of(item));
        // 3,000 취소 후 잔액 7,000
        PaymentCanceledEvent event = new PaymentCanceledEvent("order-1", 100L, 1, 3_000, 7_000, false, java.time.Instant.now());

        service.reflectCancellation(event);

        assertThat(item.getAmount()).isEqualTo(7_000);
        assertThat(item.getStatus()).isEqualTo(SettlementItemStatus.CONFIRMED);
        verify(itemRepository).saveAndFlush(item);
    }

    @Test
    @DisplayName("부분취소 멱등: 같은 취소 이벤트가 중복 배달돼도 잔액으로 세팅하므로 이중 차감되지 않는다")
    void reflectCancellationIsIdempotentOnRedelivery() {
        SettlementItem item = confirmedItem(100L, "order-1", 10_000);
        when(itemRepository.findByPaymentId(100L)).thenReturn(Optional.of(item));
        PaymentCanceledEvent event = new PaymentCanceledEvent("order-1", 100L, 1, 3_000, 7_000, false, java.time.Instant.now());

        service.reflectCancellation(event); // 1차
        service.reflectCancellation(event); // 재배달(at-least-once)

        // 델타 차감이면 4,000이 됐겠지만, 절대 잔액 세팅이라 7,000 유지 — 이중 차감 없음.
        assertThat(item.getAmount()).isEqualTo(7_000);
        verify(itemRepository, times(2)).saveAndFlush(item);
    }

    @Test
    @DisplayName("SETTLED 후 취소: 항목 미변경 + postsettle 카운터 증가(사후 조정 대상)")
    void reflectCancellationAfterSettledCountsOnly() {
        SettlementItem item = confirmedItem(100L, "order-1", 10_000);
        item.markSettled(1L); // CONFIRMED → SETTLED
        when(itemRepository.findByPaymentId(100L)).thenReturn(Optional.of(item));
        PaymentCanceledEvent event = new PaymentCanceledEvent("order-1", 100L, 1, 10_000, 0, true, java.time.Instant.now());

        service.reflectCancellation(event);

        assertThat(item.getStatus()).isEqualTo(SettlementItemStatus.SETTLED); // 미변경
        assertThat(item.getAmount()).isEqualTo(10_000);                       // 미변경
        verify(itemRepository, never()).saveAndFlush(any());
        assertThat(meterRegistry.counter("settlement.postsettle.cancel").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("취소: 정산에 없는 결제면 무시")
    void reflectCancellationMissingItemIsIgnored() {
        when(itemRepository.findByPaymentId(999L)).thenReturn(Optional.empty());
        PaymentCanceledEvent event = new PaymentCanceledEvent("order-x", 999L, 1, 10_000, 0, true, java.time.Instant.now());

        service.reflectCancellation(event);

        verify(itemRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("취소: 이미 CANCELED면 멱등하게 무시")
    void reflectCancellationIdempotentOnAlreadyCanceled() {
        SettlementItem item = confirmedItem(100L, "order-1", 10_000);
        item.cancel(); // → CANCELED
        when(itemRepository.findByPaymentId(100L)).thenReturn(Optional.of(item));
        PaymentCanceledEvent event = new PaymentCanceledEvent("order-1", 100L, 1, 10_000, 0, true, java.time.Instant.now());

        service.reflectCancellation(event);

        assertThat(item.getStatus()).isEqualTo(SettlementItemStatus.CANCELED);
        verify(itemRepository, never()).saveAndFlush(any());
    }
}
