package com.beomsu.becommerce.ledger.web;

import com.beomsu.becommerce.ledger.internal.AccountType;
import com.beomsu.becommerce.ledger.internal.LedgerBalance;
import com.beomsu.becommerce.ledger.internal.LedgerService;
import com.beomsu.becommerce.ledger.internal.LedgerView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 원장 감사 어드민 컨트롤러 — 복식부기 원장을 들여다보는 읽기 전용 표면.
 *
 * <p>append-only 원장에 분개는 계속 쌓이는데 그걸 볼 방법이 없었다. 운영/감사자가 최근 결제 승인·취소
 * 분개와 <b>차변·대변 균형</b>을 확인할 수 있게 노출한다. {@code /api/v1/admin/**}이라 ADMIN 롤 전용이다.
 */
@RestController
@RequestMapping("/api/v1/admin/ledger")
public class LedgerAdminController {

    private final LedgerService ledgerService;

    public LedgerAdminController(LedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    /** 최근 원장 트랜잭션 50건 — 각 분개와 균형(balanced) 여부 포함. */
    @GetMapping
    public List<LedgerView> recent() {
        return ledgerService.recentTransactions();
    }

    /**
     * 계정별 잔액 — 분개 합으로 파생. 원장이 유일한 진실이라 매번 다시 센다.
     *
     * <p>부호는 분개 방향 그대로다. {@code SALES} 는 대변으로 쌓이므로 음수가 정상이다.
     */
    @GetMapping("/balances")
    public List<LedgerBalance> balances() {
        return ledgerService.balances();
    }

    /** 한 계정의 잔액. */
    @GetMapping("/balances/{account}")
    public LedgerBalance balance(@PathVariable AccountType account) {
        return ledgerService.balance(account);
    }
}
