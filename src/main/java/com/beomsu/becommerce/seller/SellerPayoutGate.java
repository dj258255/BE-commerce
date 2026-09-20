package com.beomsu.becommerce.seller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>이 판매자에게 돈을 내보내도 되는가.</b> 정산이 지급 직전에 묻는 자리다.
 *
 * <p><b>왜 정산이 판매자를 직접 안 읽나</b>: 읽게 하면 정산이 심사 규칙까지 알게 된다.
 * 규칙이 바뀔 때마다 두 모듈을 같이 고쳐야 하고, 모듈 경계가 이름만 남는다.
 * 그래서 판매자 쪽이 <b>예/아니오와 그 이유</b>만 돌려준다.
 *
 * <h3>모르는 것은 통과가 아니다</h3>
 * 판매자를 못 찾으면 <b>막는다.</b> 없는 판매자에게 돈이 나가는 것이 잘못된 보류보다 나쁘다.
 * 아직 심사 안 한 판매자({@code PENDING_SCREENING})도 막는다 —
 * <b>안 본 것과 통과는 다르다.</b> 이 시스템이 결제 미확정을 실패로 안 적는 것과 같은 이유다.
 *
 * <h3>플랫폼 직판은 이 게이트가 막는 대상이 아니다</h3>
 * 이 게이트가 보는 것은 <b>외부로 나가는 지급</b>이다. 플랫폼이 자기 매출을 자기 장부에
 * 적는 것은 거기 해당하지 않는다. 예전에는 {@code sellerId == null} 이 그 판단을 했고,
 * 플랫폼이 자기 판매자 행을 갖게 된 뒤(V49)에는 {@link #PLATFORM_SELLER_ID} 가 이어받았다.
 * <b>컬럼 nullable 을 고치면서 누가 제재 명단 대조를 받는지까지 바뀌면 안 된다.</b>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SellerPayoutGate {

    /**
     * 플랫폼 자신의 판매자 행. <b>{@code V49} 가 이 id 로 박아 넣는다.</b>
     *
     * <p>플랫폼 직판을 {@code null} 로 적던 것을 대신한다. 판매자가 없는 것이 아니라
     * <b>파는 쪽이 플랫폼인 것</b>이라, 그 사실을 행 하나로 적었다.
     */
    public static final long PLATFORM_SELLER_ID = 1L;

    private final SellerRepository sellers;

    /** @param sellerId 정산을 받을 판매자. 플랫폼 직판이면 {@link #PLATFORM_SELLER_ID} 다 */
    @Transactional(readOnly = true)
    public Decision check(long sellerId) {
        if (sellerId == PLATFORM_SELLER_ID) {
            return Decision.allowed("플랫폼 직판 — 외부로 나가는 지급이 아니다");
        }
        return sellers.findById(sellerId)
                .map(s -> s.getStatus().payable()
                        ? Decision.allowed("심사 통과")
                        : Decision.blocked("판매자 상태가 " + s.getStatus() + " 다"))
                .orElseGet(() -> {
                    // 없는 판매자에게 돈이 나가는 것이 잘못된 보류보다 나쁘다.
                    log.warn("[payout-gate] 판매자를 못 찾아 지급을 막는다 sellerId={}", sellerId);
                    return Decision.blocked("판매자를 찾지 못했다");
                });
    }

    /** @param allowed 내보내도 되는가 @param reason 왜 그렇게 판단했나. 막힌 이유는 화면에 그대로 나간다 */
    public record Decision(boolean allowed, String reason) {
        static Decision allowed(String reason) { return new Decision(true, reason); }
        static Decision blocked(String reason) { return new Decision(false, reason); }
    }
}
