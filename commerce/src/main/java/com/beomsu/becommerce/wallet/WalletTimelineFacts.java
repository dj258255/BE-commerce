package com.beomsu.becommerce.wallet;

import com.beomsu.becommerce.wallet.internal.WalletTransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/** 월렛이 타임라인에 내주는 사실 (ADR-011). 잔액 추이까지 함께 준다. */
@Service
public class WalletTimelineFacts {

    private final WalletTransactionRepository repository;

    WalletTimelineFacts(WalletTransactionRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<WalletFact> findByOrderNo(String orderNo) {
        return repository.findByOrderNoOrderByIdAsc(orderNo).stream()
                .map(t -> new WalletFact(t.getCreatedAt(), t.getType().name(),
                        t.getAmount(), t.getBalanceAfter()))
                .toList();
    }

    /** {@code balanceAfter}를 함께 주는 이유: 잔액이 언제 얼마였는지가 분쟁 대응의 근거가 된다. */
    public record WalletFact(Instant at, String type, long amount, long balanceAfter) {
    }
}
