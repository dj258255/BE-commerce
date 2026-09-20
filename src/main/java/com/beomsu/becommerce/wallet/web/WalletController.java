package com.beomsu.becommerce.wallet.web;

import com.beomsu.becommerce.wallet.WalletService;
import com.beomsu.becommerce.wallet.internal.WalletView;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.Map;

/**
 * 선불 월렛 REST 컨트롤러 — wallet 모듈의 사용자 표면.
 *
 * <p>충전(전금법 기명 한도 내)·잔액 조회·거래 이력을 제공한다. userId는 인증 principal에서 얻어
 * 본인 월렛만 조회·변경한다(다른 사용자 잔액 접근 불가). 결제 차감(use)·환불(refund)은 체크아웃 사가가
 * 결제 수단으로 호출한다.
 */
@RestController
@RequestMapping("/api/v1/wallet")
public class WalletController {

    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    /** 잔액 + 최근 거래 이력. */
    @GetMapping
    public WalletView myWallet(Principal principal) {
        return walletService.myWallet(Long.parseLong(principal.getName()));
    }

    /** 충전 — 전금법 기명 한도 초과 시 LIMIT_EXCEEDED. 충전 후 잔액을 반환한다. */
    @PostMapping("/charge")
    public Map<String, Long> charge(@RequestBody ChargeRequest request, Principal principal) {
        long balance = walletService.charge(Long.parseLong(principal.getName()), request.amount());
        return Map.of("balance", balance);
    }

    public record ChargeRequest(long amount) {
    }
}
