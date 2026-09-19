package com.beomsu.pay.settlement.internal;

import com.beomsu.pay.settlement.SettlementPaidOutEvent;
import com.beomsu.pay.escrow.EscrowReleasedEvent;
import com.beomsu.pay.payment.PaymentCanceledEvent;
import com.beomsu.pay.payment.PaymentConfirmedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.Collectors;
import com.beomsu.pay.seller.SellerPayoutGate;
import java.util.Map;
import java.util.Optional;

/**
 * 정산 서비스 — 에스크로 생명주기에 정렬된 적재·확정·집계.
 *
 * <p><b>도메인 정렬</b>: 에스크로는 "구매확정 전까지 판매자 정산 보류"를 약속한다. 따라서 결제 승인은
 * 정산 항목을 곧바로 정산 가능으로 만들지 않고 {@code PENDING_CONFIRMATION}(구매확정 대기)으로만 적재한다.
 * 에스크로 릴리스(={@link EscrowReleasedEvent}, 구매확정)가 와야 {@code CONFIRMED}(정산 가능)로 전이하고,
 * 배치는 <b>CONFIRMED만</b> 집계해 {@code SETTLED}로 넘긴다 — 구매확정 전 항목은 지급에서 빠진다(보류의 실현).
 *
 * <p><b>취소 반영</b>: 취소 이벤트를 구독해 정산액을 되돌린다. 전액취소는 항목을 CANCELED로 제외하고,
 * 부분취소는 금액을 차감한다. 단 이미 SETTLED(집계 완료)된 항목은 그 정산을 고치지 않고
 * {@link SettlementAdjustment}(회수 대기)로 남겨 <b>차기 정산에서 음수로 회수</b>한다.
 *
 * <p>서비스 루프로 집계하지만, 대용량은 Spring Batch로 확장한다
 * (chunk 단위 커밋 · cursor 기반 읽기 · 날짜/가맹점 partitioning).
 *
 * <p>수수료(fee)와 수수료 부가세(feeVat)는 지급 확정 시 원장 비용 계정(PG_FEE)으로 분개된다.
 * settlement 는 {@link SettlementPaidOutEvent} 를 발행만 하고, 분개는 ledger 모듈이 한다.
 */
@Service
public class SettlementService {

    /**
     * 정산 기준 타임존. 국내 가맹점 정산은 예외 없이 <b>KST 영업일</b> 기준이다.
     *
     * <p>UTC로 잡으면 KST 00:00~09:00에 구매확정된 건이 <b>전날</b>로 스탬프된다. 그 날짜의 배치가
     * 이미 지나갔다면 그 건은 어느 배치에도 다시 잡히지 않아 영구 미정산이 된다 — 집계 기준일을
     * 릴리스일로 재스탬프해 막았던 것과 정확히 같은 실패 모드라, 타임존도 도메인 상수로 못 박는다.
     */
    public static final ZoneId SETTLEMENT_ZONE = ZoneId.of("Asia/Seoul");

    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);

    private final SettlementItemRepository itemRepository;
    private final SettlementRepository settlementRepository;
    private final SettlementAdjustmentRepository adjustmentRepository;
    private final MeterRegistry meterRegistry;
    /** 이 판매자에게 돈을 내보내도 되는가. 판매자 모듈이 답한다 — 정산이 심사 규칙을 알 필요는 없다. */
    private final SellerPayoutGate payoutGate;

    /**
     * 이 시스템이 만드는 정산의 통화.
     *
     * <p>지금은 KRW 하나다. 통화를 상수로 뽑아둔 이유는, 다통화를 붙일 때 <b>여기서 갈라야 한다</b>는
     * 것을 표시하기 위해서다. 하루치를 통화별로 나눠 각각 정산을 만들고, 그때 수수료 반올림도
     * 통화의 최소 단위 지수를 따라야 한다({@link com.beomsu.pay.shared.Money#fractionDigits()}).
     */
    private static final String SETTLEMENT_CURRENCY = "KRW";

    /** 정산 수수료율(basis point). 270 bps = 2.7%. KRW는 소수점이 없으므로 정수 연산으로 확정. */
    private final long feeBps;

    /** 지급예정일 = settlementDate + 이 영업일 수(주말 skip). */
    private final int payoutDays;

    /**
     * 틱 하나가 읽는 최대 페이지 수. 틱당 처리량 상한은 {@code maxPages × read-chunk-size} 다.
     *
     * <p>기본 20 이면 기본 chunk 500 과 곱해 틱당 1만 건이다. 한 장만 읽던 이전 동작의 20 배다.
     */
    private final int maxPages;

    /**
     * 구매확정이 왔는데 정산 항목이 없을 때 이를 사고로 볼지.
     *
     * <p>기본은 {@code false} 다. 인프로세스에서는 이벤트 순서 레이스라 재전달로 풀린다.
     * <b>서비스 분리 전환 기간에만 켠다</b> — 그때는 같은 상황이 영구 누락이기 때문이다(ADR-024).
     */
    private final boolean missingItemIsIncident;

    public SettlementService(SettlementItemRepository itemRepository,
                             SettlementRepository settlementRepository,
                             SettlementAdjustmentRepository adjustmentRepository,
                             MeterRegistry meterRegistry,
                             SellerPayoutGate payoutGate,
                             @Value("${app.settlement.fee-bps:270}") long feeBps,
                             @Value("${app.settlement.payout-business-days:2}") int payoutDays,
                             @Value("${app.settlement.missing-item-is-incident:false}") boolean missingItemIsIncident,
                             @Value("${app.batch.settlement-max-pages:20}") int maxPages) {
        this.itemRepository = itemRepository;
        this.settlementRepository = settlementRepository;
        this.adjustmentRepository = adjustmentRepository;
        this.meterRegistry = meterRegistry;
        this.payoutGate = payoutGate;
        this.feeBps = feeBps;
        this.payoutDays = payoutDays;
        this.missingItemIsIncident = missingItemIsIncident;
        this.maxPages = maxPages > 0 ? maxPages : 1;
    }

    /**
     * 결제 승인 이벤트를 정산 항목으로 적재한다 — 상태는 PENDING_CONFIRMATION(구매확정 대기).
     *
     * <p>멱등: 같은 paymentId가 이미 적재됐으면 아무 것도 하지 않는다. 승인 시각은 KST 기준
     * 날짜({@code confirmedDate})로 스냅샷해, 일 단위 배치가 이 날짜로 집계한다.
     */
    @Transactional
    public void registerConfirmedPayment(PaymentConfirmedEvent event) {
        if (itemRepository.existsByPaymentId(event.paymentId())) {
            return; // 멱등: 이미 적재함
        }
        // 적재 시점의 confirmedDate는 승인일 placeholder다(PENDING은 집계 대상이 아님). 실제 집계
        // 기준일은 구매확정(릴리스) 시 confirmSettlement가 릴리스일로 재스탬프한다.
        LocalDate approvalDate = LocalDate.ofInstant(event.approvedAt(), SETTLEMENT_ZONE);
        // 최초 INSERT는 save로 충분(신규 영속 → flush는 트랜잭션 커밋이 처리).
        // 판매자를 따로 안 실어 보내는 주문은 플랫폼이 판 것이다. 그 사실을 판매자 id 로 적는다.
        itemRepository.save(SettlementItem.of(
                event.paymentId(), event.orderNo(), event.amount(), approvalDate,
                SellerPayoutGate.PLATFORM_SELLER_ID));
    }

    /**
     * 에스크로 릴리스(구매확정) → 정산 항목을 CONFIRMED로 전이하고 집계 기준일을 <b>릴리스일로
     * 재스탬프</b>한다. <b>죽어 있던 {@link EscrowReleasedEvent}를 구독해 정산을 구매확정 시점으로
     * 옮기는 핵심.</b>
     *
     * <p>재스탬프가 필수다 — 에스크로 홀드는 다일(기본 7일)이라 승인일 D의 항목이 D+7에 CONFIRMED된다.
     * 승인일 그대로 두면 {@code settle(D)}는 D+1에 이미 지났고 재실행도 멱등 skip돼 <b>영구 미정산</b>이
     * 된다. 릴리스일 R로 재스탬프하면 R+1의 {@code settle(R)}이 정확히 집계한다.
     *
     * <p>항목이 없으면 {@code settlement.confirm.missing_item} 을 올리고 warn 후 return한다 —
     * 승인/릴리스 이벤트 순서 레이스(릴리스가 적재보다 먼저 도착) 방어. Outbox at-least-once라
     * 릴리스는 재전달되므로, 다음 배달에서 적재된 항목을 만나 전이한다.
     * {@code app.settlement.missing-item-is-incident} 를 켜면 대신 예외를 던진다(전환 기간, ADR-024).
     * 이 트랜잭션 안에서 로드한 엔티티라 커밋 시 dirty-check flush되지만, 전이 의도를 명시하려 saveAndFlush한다.
     *
     * @param orderNo     릴리스된 주문 번호
     * @param releaseDate 릴리스(구매확정)가 일어난 날짜 — 정산 집계 기준일로 재스탬프된다
     */
    @Transactional
    public void confirmSettlement(String orderNo, LocalDate releaseDate) {
        Optional<SettlementItem> found = itemRepository.findByOrderNo(orderNo);
        if (found.isEmpty()) {
            // 인프로세스에서는 이벤트 순서 레이스이고 재전달로 풀린다. 그래서 경고만 찍고 지나간다.
            //
            // <b>서비스를 분리한 뒤에는 같은 코드가 다른 뜻이 된다.</b> 새 서비스가 빈 저장소로
            // 뜨면, 전환 전에 승인된 주문의 항목은 옛 저장소에만 있다. 그 구매확정이 도착해도
            // 대응할 항목이 없고, 재전달해도 영원히 생기지 않는다. 성공으로 처리되어 DLT 에도
            // 안 가므로 <b>조용한 지급 누락</b>이 된다. 에스크로 보류가 7일이라 전환 직전
            // 7일치가 통째로 여기 걸린다(ADR-024).
            //
            // 그래서 <b>세기부터 한다.</b> 지금까지는 이 일이 몇 번 일어났는지조차 남지 않았다.
            meterRegistry.counter("settlement.confirm.missing_item").increment();
            if (missingItemIsIncident) {
                // 전환 기간에는 이것이 레이스가 아니라 사고 신호다. 예외로 올려 컨슈머 재시도와
                // DLT 격리에 태운다. 조용히 지나가는 것보다 시끄럽게 막히는 편이 낫다.
                throw new SettlementException("SETTLEMENT_ITEM_MISSING",
                        "정산 항목 없이 구매확정이 도착했다. 전환 기간 사고 신호: " + orderNo);
            }
            log.warn("에스크로 릴리스 수신했으나 정산 항목 없음 orderNo={} — 이벤트 순서 레이스, 재전달 대기", orderNo);
            return;
        }
        SettlementItem item = found.get();
        item.confirm(releaseDate); // 멱등: PENDING_CONFIRMATION일 때만 CONFIRMED + 집계일 재스탬프
        itemRepository.saveAndFlush(item);
    }

    /**
     * 결제 취소를 정산에 반영한다 — 전액취소는 제외(CANCELED), 부분취소는 금액 차감.
     *
     * <p><b>SETTLED 항목</b>: 이미 그날 정산에 집계돼 지급 대상이 됐으므로 그 정산을 고치지 않는다.
     * 대신 {@link SettlementAdjustment}를 회수 대기로 남기고 차기 정산이 음수로 반영한다.
     * 예전에는 카운터만 올렸는데, 그러면 "몇 건 있었다"는 알아도 <b>어떤 주문을 얼마 조정할지</b>를
     * 복구할 수 없었다. 원장이 취소 이력을 갖고 있지만 그건 근거지 <b>실행할 일</b>이 아니다.
     *
     * <p><b>부분취소 멱등</b>: 이벤트가 <b>취소 후 잔액(절대값)</b>을 담고, 항목 금액을 그 잔액으로 세팅한다
     * (델타 차감이 아님). 중복 배달돼도 같은 값이 되어 이중 차감되지 않는다.
     *
     * <p>다만 <b>절대값 세팅은 순서 역전에는 안전하지 않다</b>. 2차 취소가 먼저 소비되면 늦게 온 1차가
     * 잔액을 되돌린다. 파티션 순서에 기대지 않고 {@code cancelSeq}로 직접 막는다
     * ({@link SettlementItem#applySettleableBalance}).
     */
    @Transactional
    public void reflectCancellation(PaymentCanceledEvent event) {
        Optional<SettlementItem> found = itemRepository.findByPaymentId(event.paymentId());
        if (found.isEmpty()) {
            return; // 정산에 잡히지 않은 결제(비-정산 대상 등) — skip
        }
        SettlementItem item = found.get();

        if (item.getStatus() == SettlementItemStatus.SETTLED) {
            // 이미 지급 대상으로 집계됐다. 과거 정산을 고치지 않고 <차기 정산에서 음수로 회수>한다.
            // 카운터만 올리면 "몇 건 있었다"는 알지만 어떤 주문을 얼마 조정할지는 복구할 수 없다.
            recordClawback(event, item);
            return;
        }
        if (item.getStatus() == SettlementItemStatus.CANCELED) {
            return; // 멱등: 이미 취소 제외됨
        }

        if (event.fullyCanceled()) {
            item.cancel(); // PENDING_CONFIRMATION/CONFIRMED → CANCELED (멱등: CANCELED 가드)
        } else {
            // 델타 차감이 아니라 취소 후 잔액(절대값)으로 세팅 → 재배달돼도 이중 차감 없음(멱등).
            item.applySettleableBalance(event.cancelSeq(), event.settleableBalance());
        }
        itemRepository.saveAndFlush(item);
    }

    /**
     * 정산 배치의 핵심 — 해당 날짜의 <b>CONFIRMED</b> 항목을 집계해 정산을 만든다.
     *
     * <p>흐름: CONFIRMED 조회 → 총액(gross) 합산 → 수수료(feeBps 내림)·부가세 → 지급예정일(영업일)
     * → 정산 생성·저장 → 항목 SETTLED. 구매확정 안 된 PENDING_CONFIRMATION은 집계에서 빠진다 —
     * 이것이 "구매확정 전 보류"의 실현이다. 재실행 멱등: 그 날짜 정산이 이미 있으면
     * (existsBySettlementDate) 아무 것도 하지 않고 null 반환. 집계할 CONFIRMED 항목이 없어도
     * null 반환(빈 정산은 만들지 않는다).
     *
     * @return 생성된 정산, 재실행이거나 대상이 없으면 null
     */
    @Transactional
    public Settlement settle(LocalDate date) {
        // 그 날짜 <이하>의 미정산 재고를 전부 본다. 날짜가 정확히 맞는 것만 모으면,
        // 그 날짜 정산이 만들어진 뒤 늦게 확정된 항목이 영영 집계되지 않는다.
        //
        // <b>페이지를 여러 장 읽는다. 다만 무한히 읽지는 않는다.</b>
        // 한 장만 읽던 때는 하루 처리량이 chunk 하나(기본 500건)로 묶여 있었다. 틱이 24시간에
        // 한 번이고 같은 날짜 재실행은 판매자별로 멱등 skip 되므로, <b>확정이 하루 500건을 넘으면
        // 남는 양이 매일 쌓이고 지연 일수가 무한히 커진다.</b> 트래픽의 성질이 아니라 코드가 정한
        // 천장이었다(ADR-023).
        //
        // 그렇다고 빌 때까지 돌면 영영 안 끝나는 건 하나가 배치를 붙잡는다(read-chunk-size 주석의
        // 근거다). 그래서 <b>틱당 최대 페이지 수</b>로 상한을 둔다. 틱당 일이 여전히 예측 가능하고,
        // 상한에 닿으면 그건 지표로 남는다.
        List<SettlementItem> all = readUpToMaxPages(date);
        if (all.isEmpty()) {
            return null; // 집계할 대상 없음 → 빈 정산을 만들지 않는다
        }

        // <b>판매자별로 가른다.</b> 정산은 누군가에게 하는 것이고, 받는 쪽이 다르면 다른 정산이다.
        // 플랫폼 직판도 자기 판매자 id 로 묶인다.
        //
        // 예전에는 직판을 null 로 적어 이 자리에서 groupingBy 를 못 썼다 — 맵에 넣기 전에
        // 키를 검사해 거부한다(element cannot be mapped to a null key). 값이 늘 있어서 풀렸다.
        Map<Long, List<SettlementItem>> bySeller = all.stream()
                .collect(Collectors.groupingBy(SettlementItem::getSellerId));

        Settlement first = null;
        for (Map.Entry<Long, List<SettlementItem>> e : bySeller.entrySet()) {
            Settlement made = settleOne(date, e.getKey(), e.getValue());
            if (first == null) {
                first = made;
            }
        }
        return first;
    }

    /**
     * 판매자 하나의 정산.
     *
     * <p><b>멱등 검사가 판매자별이어야 한다.</b> 날짜만 보고 건너뛰면 나중에 등록된 판매자의
     * 정산이 영영 안 나간다. 이 키는 이미 한 번 조용히 틀려서 지급이 통째로 빠진 적이 있는
     * 자리라, 판매자를 더하면서 검사도 같이 옮겼다.
     */
    private Settlement settleOne(LocalDate date, long sellerId, List<SettlementItem> items) {
        if (settlementRepository.existsBySettlementDateAndCurrencyAndSellerId(
                date, SETTLEMENT_CURRENCY, sellerId)) {
            log.info("정산 재실행 감지 → 건너뜀 date={} sellerId={}", date, sellerId);
            return null;
        }

        // <b>집계는 하되 지급을 막는다.</b> 여기서 집계까지 건너뛰면 나중에 심사가 풀렸을 때
        // 그 날짜 매출이 영영 안 잡힌다 — 이 집계 키는 이미 한 번 조용히 틀려 지급이 통째로
        // 빠졌던 자리다. 그래서 정산은 만들고 지급 가능 여부만 표시한다.
        var gate = payoutGate.check(sellerId);

        long gross = 0L;
        for (SettlementItem item : items) {
            gross = Math.addExact(gross, item.getAmount()); // 오버플로 시 즉시 실패
        }
        long fee = calculateFee(gross);
        long feeVat = fee / 10; // 수수료 부가세 10%(내림)
        LocalDate payoutDate = BusinessDays.plusBusinessDays(date, payoutDays);

        // 회수 대기분을 먼저 반영한다 — 총액이 정해진 뒤에 수수료를 계산해야 맞다.
        Settlement settlement = settlementRepository.save(
                Settlement.of(date, SETTLEMENT_CURRENCY, gross, fee, feeVat, items.size(),
                        payoutDate, sellerId));
        long adjustedGross = applyPendingAdjustments(gross, settlement);
        if (adjustedGross != gross) {
            long adjFee = calculateFee(adjustedGross);
            settlement.reviseAmounts(adjustedGross, adjFee, adjFee / 10);
            settlementRepository.saveAndFlush(settlement);
        }
        items.forEach(item -> item.markSettled(settlement.getId()));

        // 금액이 다 정해진 뒤에 막는다. 앞에서 막으면 조정 반영이 안 돌아 <b>금액이 틀린 채로</b>
        // 보류된다 — 나중에 풀었을 때 그 금액이 그대로 나간다.
        if (!gate.allowed()) {
            settlement.holdPayout(gate.reason());
            settlementRepository.saveAndFlush(settlement);
            meterRegistry.counter("settlement.payout.held").increment();
            log.warn("[payout-gate] 지급 보류 date={} sellerId={} 이유={}", date, sellerId, gate.reason());
        }
        return settlement;
    }

    /**
     * 정산된 뒤 온 취소를 회수 대기로 남긴다.
     *
     * <p>{@code (orderNo, cancelSeq)} 유니크가 중복 배달을 막는다. 조정을 만들지 못해도
     * 취소 처리 자체를 실패시키지 않는다 — 다만 <b>조용히 넘기지는 않는다</b>.
     */
    private void recordClawback(PaymentCanceledEvent event, SettlementItem item) {
        long recoverable = item.getAmount() - Math.max(0L, event.settleableBalance());
        if (recoverable <= 0) {
            return;   // 회수할 게 없다
        }
        if (adjustmentRepository.existsByOrderNoAndCancelSeq(event.orderNo(), event.cancelSeq())) {
            return;   // 멱등
        }
        adjustmentRepository.save(SettlementAdjustment.pendingClawback(
                event.orderNo(), event.paymentId(), item.getSettlementId(),
                event.cancelSeq(), recoverable));
        log.warn("정산 완료 후 취소 — 차기 정산 회수 대기 등록 orderNo={} 회수={}",
                event.orderNo(), recoverable);
        meterRegistry.counter("settlement.postsettle.cancel").increment();
    }

    /**
     * 회수 대기분을 이번 정산 총액에서 뺀다.
     *
     * <p><b>총액을 넘는 회수는 자동으로 처리하지 않는다.</b> 음수 지급은 "돈을 돌려받는" 일이라
     * 지급 파이프라인이 아니라 별도 청구 절차다. 조용히 0으로 깎으면 회수액이 증발한다.
     * 그런 건 {@code REVIEW_REQUIRED}로 남겨 사람이 본다.
     */
    private long applyPendingAdjustments(long gross, Settlement settlement) {
        List<SettlementAdjustment> pending =
                adjustmentRepository.findByStatus(SettlementAdjustmentStatus.PENDING);
        long adjusted = gross;
        for (SettlementAdjustment a : pending) {
            long next = adjusted + a.getAdjustmentAmount();   // adjustmentAmount는 음수
            if (next < 0) {
                a.requireReview();
                meterRegistry.counter("settlement.adjustment.review_required").increment();
                continue;
            }
            adjusted = next;
            a.applyTo(settlement.getId(), settlement.getSettlementDate());
            meterRegistry.counter("settlement.adjustment.applied").increment();
        }
        adjustmentRepository.saveAll(pending);
        return adjusted;
    }

    /**
     * 수수료를 basis point로 계산해 내림한다. floor(gross * feeBps / 10000)을 정수 연산으로 확정한다
     * (double 금지 — 화폐 정수 연산 원칙). 오버플로는 {@code Math.multiplyExact}로 방어한다.
     *
     * <p>수수료 부가세는 지급 확정 시 원장 비용 계정으로 분개된다(ledger 모듈이 한다).
     */
    private long calculateFee(long gross) {
        return Math.multiplyExact(gross, feeBps) / 10000;
    }

    /**
     * 배치 한 번이 읽는 상한. 남은 것은 다음 주기가 가져간다.
     *
     * <p><b>필드에 기본값을 둔다.</b> {@code @Value} 는 스프링이 만들어 줄 때만 채워지는데,
     * 단위 테스트는 이 서비스를 직접 생성한다. 초기값이 없으면 0 이 되어 페이지 크기가
     * 0 이라고 터진다 — 실제로 그렇게 깨졌다.
     */
    @org.springframework.beans.factory.annotation.Value("${app.batch.read-chunk-size:500}")
    private int readChunkSize = 500;

    /**
     * <b>설정이 0 이나 음수여도 배치를 죽이지 않는다.</b> 잘못된 설정 하나로 돈을 다루는
     * 배치가 멈추는 것보다, 기본값으로 도는 편이 낫다.
     */
    private int pageSize() {
        return readChunkSize > 0 ? readChunkSize : 500;
    }

    /**
     * 정렬을 박은 페이지 요청.
     *
     * <p><b>정렬이 없으면 페이지를 여러 장 읽을 수 없다.</b> 순서를 보장하지 않는 조회에
     * offset 페이징을 얹으면 같은 행이 두 장에 나오거나 아예 빠진다. 돈을 세는 배치에서
     * 그건 이중 지급이거나 누락이다. id 오름차순으로 고정한다.
     */
    private org.springframework.data.domain.Pageable chunk(int page) {
        return org.springframework.data.domain.PageRequest.of(page, pageSize(),
                org.springframework.data.domain.Sort.by(
                        org.springframework.data.domain.Sort.Direction.ASC, "id"));
    }

    /**
     * 판매자 공정성 정책 — {@code id}(기본, 전역 id 오름차순) 또는 {@code round-robin}(판매자별 한 장씩).
     *
     * <p>기본값이 {@code id} 인 이유: 정책을 바꾸면 <b>지급일 약속이 바뀐다</b>(사업 결정이다).
     * 실측으로 대가를 확인한 뒤 운영이 명시적으로 켠다(ADR-027). 꺼져 있으면 기존 동작 그대로다.
     */
    @org.springframework.beans.factory.annotation.Value("${app.settlement.fairness-policy:id}")
    private String fairnessPolicy = "id";

    /**
     * 틱당 최대 {@code maxPages} 장까지 읽어 모은다.
     *
     * <p>읽기는 전부 쓰기 전에 끝난다. 같은 트랜잭션 안에서 상태가 바뀌기 전에 다 읽으므로
     * offset 페이징이 흔들리지 않는다.
     *
     * <p>상한에 닿았다는 것은 <b>밀린 재고가 이 틱으로도 안 빠졌다</b>는 뜻이다. 조용히 넘기지
     * 않고 지표와 경고로 남긴다.
     */
    private List<SettlementItem> readUpToMaxPages(LocalDate date) {
        return "round-robin".equalsIgnoreCase(fairnessPolicy)
                ? readRoundRobin(date)
                : readByIdOrder(date);
    }

    /**
     * 전역 id 오름차순으로 읽는다(기본).
     *
     * <p><b>불공정하다.</b> 판매자 한 곳이 앞 페이지를 다 채우면(오래된 id 를 많이 가진 대형 판매자)
     * 뒤 판매자의 항목은 이 틱에 안 읽히고 다음 틱으로 밀린다. 물량이 작을 때는 드러나지 않는다
     * (ADR-027).
     */
    List<SettlementItem> readByIdOrder(LocalDate date) {
        List<SettlementItem> all = new java.util.ArrayList<>();
        int page = 0;
        while (true) {
            List<SettlementItem> got = itemRepository.findByStatusAndConfirmedDateLessThanEqual(
                    SettlementItemStatus.CONFIRMED, date, chunk(page));
            all.addAll(got);
            if (got.size() < pageSize()) {
                break; // 마지막 장이다
            }
            page++;
            if (page >= maxPages) {
                meterRegistry.counter("settlement.batch.page_cap_reached").increment();
                log.warn("정산 배치가 틱당 페이지 상한에 닿았다 date={} 읽은건수={} 상한={}장×{}건 "
                                + "— 남은 재고는 다음 틱으로 밀린다",
                        date, all.size(), maxPages, pageSize());
                break;
            }
        }
        return all;
    }

    /**
     * 판매자 공정성 정책(라운드로빈) — <b>한 곳이 틱을 독식하지 못하게 한다.</b>
     *
     * <p>두 단계다. ① 첫 패스에서 판매자마다 {@code ceil(예산/판매자수)} 장까지 읽어 <b>상한</b>을 둔다.
     * ② 그러고도 용량이 남으면(재고가 적은 판매자가 있어서) 재고가 있는 판매자에게 한 장씩
     * <b>재분배</b>한다. 즉 소형 판매자가 다음 틱으로 밀리지 않고, 남는 용량은 버려지지 않는다.
     *
     * <p>모든 읽기가 쓰기 전에 끝나므로(트랜잭션 안에서 행 상태가 안 바뀜) 판매자별 offset 페이징이
     * 흔들리지 않는다.
     *
     * <p><b>대가</b>: 대형 판매자는 자기 차례가 줄어 지급이 늦어질 수 있다. 이건 기술이 아니라
     * <b>누구를 굶길지</b>의 문제이고, 그래서 기본값이 아니다(ADR-027).
     */
    List<SettlementItem> readRoundRobin(LocalDate date) {
        List<Long> sellers = itemRepository.findSellerIdsWithPending(
                SettlementItemStatus.CONFIRMED, date);
        if (sellers.isEmpty()) {
            return List.of();
        }
        int pagesBudget = maxPages;
        int perSellerPages = Math.max(1, (int) Math.ceil((double) pagesBudget / sellers.size()));

        java.util.Map<Long, Integer> nextPage = new java.util.HashMap<>();
        for (Long seller : sellers) {
            nextPage.put(seller, 0);
        }
        List<SettlementItem> all = new java.util.ArrayList<>();
        int pagesUsed = 0;

        // ① 판매자별 상한 패스
        for (Long seller : sellers) {
            for (int i = 0; i < perSellerPages && pagesUsed < pagesBudget; i++) {
                List<SettlementItem> got = readSellerPage(seller, date, nextPage.get(seller));
                if (got.isEmpty()) {
                    nextPage.put(seller, -1); // 재고 바닥
                    break;
                }
                nextPage.put(seller, nextPage.get(seller) + 1);
                all.addAll(got);
                pagesUsed++;
            }
        }

        // ② 잔여 용량 재분배 — 아직 재고가 있는 판매자에게 한 장씩
        boolean progressed = true;
        while (pagesUsed < pagesBudget && progressed) {
            progressed = false;
            for (Long seller : sellers) {
                if (pagesUsed >= pagesBudget) {
                    break;
                }
                Integer page = nextPage.get(seller);
                if (page == null || page < 0) {
                    continue; // 재고 바닥난 판매자는 건너뛴다
                }
                List<SettlementItem> got = readSellerPage(seller, date, page);
                if (got.isEmpty()) {
                    nextPage.put(seller, -1);
                    continue;
                }
                nextPage.put(seller, page + 1);
                all.addAll(got);
                pagesUsed++;
                progressed = true;
            }
        }

        if (pagesUsed >= pagesBudget) {
            meterRegistry.counter("settlement.batch.page_cap_reached").increment();
            log.warn("정산 배치(라운드로빈)가 틱당 상한에 닿았다 date={} 읽은건수={} 판매자수={} 상한={}장×{}건",
                    date, all.size(), sellers.size(), maxPages, pageSize());
        }
        return all;
    }

    private List<SettlementItem> readSellerPage(long sellerId, LocalDate date, int page) {
        return itemRepository.findBySellerIdAndStatusAndConfirmedDateLessThanEqualOrderByIdAsc(
                sellerId, SettlementItemStatus.CONFIRMED, date, chunk(page));
    }
}
