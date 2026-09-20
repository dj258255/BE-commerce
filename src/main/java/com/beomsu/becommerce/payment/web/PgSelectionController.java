package com.beomsu.becommerce.payment.web;

import com.beomsu.becommerce.payment.pg.PgSelector;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/**
 * 결제창을 띄우기 전에 어느 PG로 갈지 정해 준다.
 *
 * <p><b>왜 승인 단계가 아닌가.</b> 결제창 방식에서 승인에 쓰는 결제 키는 고객이 그 PG의
 * 결제창에서 인증을 마쳐야 발급된다. 그 키를 다른 PG에 보내면 모르는 거래라고 답하므로
 * <b>승인 단계에서 넘기면 성공률이 0이다.</b> 실 연동에서 확인했다. 그래서 고르는 시점을 앞으로 옮겼다.
 *
 * <p><b>왜 order 가 아니라 payment 모듈인가.</b> {@code order}의 허용 의존에 {@code payment}는
 * 있지만 그 안의 {@code pg}는 내부 패키지라 밖에서 볼 수 없다. PG를 고르는 일은 payment 의
 * 관심사이므로 창구를 여기 둔다. 프론트는 결제 시작 때 이걸 한 번 부른다.
 *
 * <p><b>진행 중인 거래는 여전히 넘기지 않는다.</b> 이건 아픈 PG를 애초에 안 고르는 장치이지,
 * 응답을 못 받은 요청을 다른 곳으로 다시 보내는 것이 아니다.
 */
@RestController
@RequestMapping("/api/v1/payments")
// 같은 모듈의 API(루트)가 참조하므로 public 이다. Modulith 문서가 짚듯 internal 에 있어도
// public 이면 컴파일러는 막지 못하며, 모듈 밖 접근은 ModularityTests 의 allowedDependencies 가 막는다.
public class PgSelectionController {

    /** 라우팅을 끄면 빈이 없다. 그때는 어댑터가 하나뿐이라 고를 것도 없다. */
    private final Optional<PgSelector> selector;

    PgSelectionController(Optional<PgSelector> selector) {
        this.selector = selector;
    }

    /**
     * 지금 결제를 시작하기에 가장 나은 PG.
     *
     * <p>차단기가 열린 PG는 후보에서 빠지므로 아픈 곳으로 고객을 보내지 않는다.
     * 라우팅이 꺼져 있으면 {@code routed=false}로 나가고 프론트는 기본 PG를 쓴다.
     *
     * <p><b>여기서 실패하면 결제 자체를 시작할 수 없다.</b> 그래서 고를 게 없어도 500을 내지 않는다.
     */
    @PostMapping("/init")
    InitResult init() {
        String provider = selector.flatMap(PgSelector::select).orElse(null);
        return new InitResult(provider, provider != null);
    }

    /**
     * @param provider 결제창을 띄울 PG. 라우팅이 꺼져 있으면 null
     * @param routed   서버가 골라 준 것인지
     */
    record InitResult(String provider, boolean routed) {
    }
}
