package com.beomsu.becommerce.receipt;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 증빙 서비스 — 증빙 종류 결정 + 현금영수증 발급/연쇄 취소.
 *
 * <p>결제 취소 시 현금영수증을 <b>연쇄 취소</b>하는 것이 이 모듈의 핵심(운영 함정). 수동 발급 건은
 * 결제만 취소하고 현금영수증을 방치하는 사고가 잦은데, 여기서는 결제 취소 이벤트를 구독해 자동으로
 * 취소한다.
 */
@Service
@RequiredArgsConstructor
public class ReceiptService {

    private final CashReceiptRepository repository;

    /** 결제수단·B2B 여부로 법정 증빙 종류를 결정한다(카드→매출전표, 현금성→현금영수증, B2B→세금계산서). */
    public EvidenceType evidenceFor(String method, boolean b2b) {
        return EvidenceResolver.resolve(method, b2b);
    }

    /**
     * 현금영수증 발급. 실제 발급은 비동기(REQUESTED → PG 콜백 → ISSUED)지만, 데모에서는 즉시 발급 처리한다.
     */
    @Transactional
    public Long issueCashReceipt(String orderNo, long amount, String receiptType) {
        CashReceipt receipt = CashReceipt.request(orderNo, amount, receiptType);
        receipt.markIssued("cr-" + orderNo);   // 운영: PG 발급 콜백에서 markIssued
        return repository.save(receipt).getId();
    }

    /** 결제 취소에 따른 현금영수증 연쇄 취소. 여러 건이면 모두, 이미 취소면 멱등 무시. */
    @Transactional
    public int cancelByOrder(String orderNo) {
        List<CashReceipt> receipts = repository.findByOrderNo(orderNo);
        receipts.forEach(CashReceipt::cancel);
        repository.saveAll(receipts);
        return receipts.size();
    }
}
