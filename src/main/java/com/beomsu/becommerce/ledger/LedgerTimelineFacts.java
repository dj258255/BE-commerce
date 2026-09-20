package com.beomsu.becommerce.ledger;

import com.beomsu.becommerce.ledger.internal.LedgerTransactionRepository;
import com.beomsu.becommerce.ledger.internal.LedgerEntry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 원장이 타임라인에 내주는 사실 (ADR-011).
 *
 * <p><b>키가 다르다.</b> 원장은 {@code (txType, sourceType, sourceId)}로 묶이고 sourceId는
 * 결제 id다 — 주문번호로 직접 못 찾는다(ADR-011 트레이드오프 5). 그래서 이 조회는
 * 주문번호가 아니라 <b>결제 id</b>를 받는다.
 *
 * <p>정산 분개({@code sourceType=SETTLEMENT})는 하루치를 묶으므로 주문 하나에 대응하지 않아
 * 여기서 다루지 않는다.
 */
@Service
public class LedgerTimelineFacts {

    private final LedgerTransactionRepository repository;

    LedgerTimelineFacts(LedgerTransactionRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<LedgerFact> findByPaymentId(long paymentId) {
        return repository.findBySourceTypeAndSourceIdOrderByIdAsc("PAYMENT", paymentId).stream()
                .map(t -> new LedgerFact(t.getCreatedAt(), t.getTxType(), t.getDescription(),
                        t.getEntries().stream().mapToLong(LedgerEntry::getAmount).max().orElse(0)))
                .toList();
    }

    /** 분개 한 건. 차·대변 각각이 아니라 <b>거래 단위</b>로 준다 — 타임라인은 요약이지 원장 화면이 아니다. */
    public record LedgerFact(Instant at, String txType, String description, long amount) {
    }
}
