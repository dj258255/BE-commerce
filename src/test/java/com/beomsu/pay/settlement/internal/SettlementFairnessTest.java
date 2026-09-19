package com.beomsu.pay.settlement.internal;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 정산 배치의 판매자 공정성 — 한 판매자가 틱 용량을 독식할 때 소형 판매자가 밀리는지(ADR-027).
 *
 * <p>읽기만 본다. 집계·금액은 {@link SettlementServiceTest} 가 본다. 상한·재분배 동작을 보려고
 * 틱 용량({@code maxPages × readChunkSize})을 작게 잡는다.
 */
class SettlementFairnessTest {

    private static final LocalDate DATE = LocalDate.of(2026, 9, 18);
    private static final long BIG = 1L;
    private static final long SMALL = 2L;
    private static final int PAGE = 2;   // readChunkSize

    private SettlementItemRepository itemRepository;
    private SettlementService service;

    private void serviceWith(int maxPages) {
        itemRepository = mock(SettlementItemRepository.class);
        com.beomsu.pay.seller.SellerPayoutGate gate = new com.beomsu.pay.seller.SellerPayoutGate(null) {
            @Override
            public Decision check(long sellerId) {
                return new Decision(true, "테스트: 심사 통과");
            }
        };
        service = new SettlementService(itemRepository, mock(SettlementRepository.class),
                mock(SettlementAdjustmentRepository.class), new SimpleMeterRegistry(), gate,
                270L, 2, false, maxPages);
        ReflectionTestUtils.setField(service, "readChunkSize", PAGE);
    }

    private static Pageable page(int p) {
        return PageRequest.of(p, PAGE, Sort.by(Sort.Direction.ASC, "id"));
    }

    private SettlementItem item(long paymentId, long sellerId) {
        return SettlementItem.of(paymentId, "ord-" + paymentId, 10_000, DATE, sellerId);
    }

    private void stubSellerPage(long seller, int pageNo, List<SettlementItem> items) {
        when(itemRepository.findBySellerIdAndStatusAndConfirmedDateLessThanEqualOrderByIdAsc(
                eq(seller), eq(SettlementItemStatus.CONFIRMED), eq(DATE), eq(page(pageNo))))
                .thenReturn(items);
    }

    @Test
    @DisplayName("id 순: 대형 판매자가 앞 페이지를 다 채우면 소형 판매자는 이 틱에 하나도 안 읽힌다")
    void idOrderStarvesSmallSeller() {
        serviceWith(2);   // 틱 용량 4건
        when(itemRepository.findByStatusAndConfirmedDateLessThanEqual(
                eq(SettlementItemStatus.CONFIRMED), eq(DATE), any(Pageable.class)))
                .thenAnswer(inv -> ((Pageable) inv.getArgument(2)).getPageNumber() == 0
                        ? List.of(item(1, BIG), item(2, BIG))
                        : List.of(item(3, BIG), item(4, BIG)));

        List<SettlementItem> read = service.readByIdOrder(DATE);

        assertThat(read).hasSize(4);
        assertThat(read).allMatch(i -> i.getSellerId() == BIG,
                "소형 판매자 항목이 하나도 안 읽혔다 = 다음 틱으로 밀린다");
    }

    @Test
    @DisplayName("라운드로빈: 첫 패스에서 소형 판매자도 읽는다 — 다음 틱으로 안 밀린다")
    void roundRobinReadsSmallSellerInSameTick() {
        serviceWith(2);   // 예산 2장, 판매자 2 → 판매자마다 1장
        when(itemRepository.findSellerIdsWithPending(SettlementItemStatus.CONFIRMED, DATE))
                .thenReturn(List.of(BIG, SMALL));
        stubSellerPage(BIG, 0, List.of(item(1, BIG), item(2, BIG)));
        stubSellerPage(SMALL, 0, List.of(item(5, SMALL)));

        List<SettlementItem> read = service.readRoundRobin(DATE);

        assertThat(read).extracting(SettlementItem::getSellerId).contains(SMALL);
        assertThat(read).hasSize(3);   // 대형 1장(2건) + 소형 1장(1건) = 예산 소진
    }

    @Test
    @DisplayName("라운드로빈: 소형 재고가 바닥나면 남은 용량이 대형에게 재분배된다")
    void roundRobinRedistributesLeftoverCapacity() {
        serviceWith(4);   // 예산 4장, 판매자 2 → 판매자마다 2장. 소형이 바닥나면 대형이 더 읽는다
        when(itemRepository.findSellerIdsWithPending(SettlementItemStatus.CONFIRMED, DATE))
                .thenReturn(List.of(BIG, SMALL));
        stubSellerPage(BIG, 0, List.of(item(1, BIG), item(2, BIG)));
        stubSellerPage(BIG, 1, List.of(item(3, BIG), item(4, BIG)));
        stubSellerPage(BIG, 2, List.of(item(6, BIG), item(7, BIG)));
        stubSellerPage(SMALL, 0, List.of(item(5, SMALL)));   // 1장에서 바닥

        List<SettlementItem> read = service.readRoundRobin(DATE);

        // 소형 1건 + 대형 3장(6건) = 7건. 버려진 용량 없이 예산을 다 쓴다.
        assertThat(read).hasSize(7);
        assertThat(read).extracting(SettlementItem::getPaymentId).contains(5L, 6L, 7L);
    }

    @Test
    @DisplayName("라운드로빈: 미정산 재고가 없으면 빈 목록(빈 정산을 만들지 않는다)")
    void roundRobinEmptyWhenNoPending() {
        serviceWith(2);
        when(itemRepository.findSellerIdsWithPending(SettlementItemStatus.CONFIRMED, DATE))
                .thenReturn(List.of());

        assertThat(service.readRoundRobin(DATE)).isEmpty();
    }
}
