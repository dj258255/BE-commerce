/**
 * 선불 충전 월렛(wallet, 페이머니) 모듈 — 카카오페이머니·토스머니·네이버페이머니와 같은 선불전자지급수단.
 *
 * <p>사용자가 미리 충전한 잔액에서 결제·차감하는 내부 자금 수단이다. point 모듈이 "적립형" 혜택이라면
 * wallet은 "선불 충전형" 결제 수단이라는 점이 다르다. 두 가지 규제/기술 논점을 코드로 반영한다:
 * <ul>
 *   <li><b>전자금융거래법 한도</b> — 선불전자지급수단 기명식 발행한도 <b>200만원</b>(금융위 유권해석,
 *       카카오페이머니 한도 근거)을 충전 시점의 도메인 규칙으로 강제한다({@code WalletException#MAX_BALANCE}).</li>
 *   <li><b>동시성</b> — 낙관적 락(@Version) + 잔액 조건부 커밋으로 이중차감·마이너스 잔액을 차단한다.
 *       잔액은 덮어쓰지 않고 {@code WalletTransaction}(append-only)에 이력을 남겨, 잔액을 이력의 파생값으로 본다.</li>
 * </ul>
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = { "shared" }
)
package com.beomsu.becommerce.wallet;

import com.beomsu.becommerce.wallet.internal.WalletTransaction;
import com.beomsu.becommerce.wallet.internal.WalletException;
