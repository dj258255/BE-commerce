package com.beomsu.becommerce.home.web;

import com.beomsu.becommerce.home.HomeComposer;
import com.beomsu.becommerce.home.HomePageView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

/**
 * 홈 API — 상점 첫 화면이 부르는 한 엔드포인트.
 *
 * <p><b>경로가 {@code /api/v1/personalization/...} 인 이유</b>: 이 경로는 이미 계약이다 —
 * 목 fixture(`personalization/web/fixtures/homepage.json`)와 Next 앱(`apps/web/lib/api.ts`)이 그
 * 경로 위에 서 있다. 모듈은 {@code home} 으로 나눴지만 <b>경로를 바꾸면 계약을 바꾸는 것</b>이라
 * 그대로 둔다(모듈 경계와 URL 경로는 다른 것이다).
 *
 * <p><b>인증</b>: 로그인한 본인의 홈만 만든다 — 컨텍스트(최근 활동)가 사용자별이기 때문이다.
 * 개인화 표면과 같은 규칙이다.
 *
 * <p><b>다른 사용자의 홈을 여는 경로는 두지 않는다.</b> 측정에 여러 사용자가 필요하면 계정을 여러 개
 * 만드는 것이 맞다 — 남의 홈을 id 로 열 수 있게 하면 그 자체가 결함이고, 실험 계기처럼 "기본 off"로
 * 잠그더라도 <b>열 이유가 없는 표면</b>이다. 이 저장소가 계기를 잠그는 규칙(실험 밖에서 열려 있으면
 * 결함)은 여기에도 그대로다.
 */
@RestController
@RequestMapping("/api/v1/personalization/homepage")
public class HomeController {

    private final HomeComposer composer;

    public HomeController(HomeComposer composer) {
        this.composer = composer;
    }

    /**
     * 본인 홈. <b>모델이 죽어도 200 이다</b> — 폴백은 정상 응답이고, 어느 쪽인지는 {@code source} 가 밝힌다.
     * 5xx 로 답하면 상점 첫 화면이 통째로 실패로 보이는데, 실제로는 보여줄 상품이 있다.
     */
    @GetMapping
    public HomePageView homepage(Principal principal) {
        // userId 는 **인증 principal** 에서 얻는다(개인화·추천·주문과 같은 규칙). 경로나 질의로 받으면
        // 남의 홈을 만들 수 있다 — 홈은 그 사용자의 활동으로 조립되므로 그게 곧 정보 노출이다.
        return composer.compose(Long.parseLong(principal.getName()));
    }
}
