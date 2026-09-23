package com.beomsu.becommerce.point.web;

import com.beomsu.becommerce.point.PointService;
import com.beomsu.becommerce.point.internal.PointView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

/**
 * 포인트 조회 REST 컨트롤러 — point 모듈의 사용자 표면.
 *
 * <p>포인트는 결제 시 차감(USE)·복원(RESTORE)·환불(REFUND)만 있고 <b>적립·조회 표면이 없어</b>
 * 사용자가 자기 포인트를 볼 수 없었다. 결제 완료 시 실결제액 기준 적립(EARN)을 붙이면서, 잔액·이력
 * 조회도 함께 노출한다. userId는 인증 principal에서 얻어 본인 포인트만 본다.
 */
@RestController
@RequestMapping("/api/v1/points")
public class PointController {

    private final PointService pointService;

    public PointController(PointService pointService) {
        this.pointService = pointService;
    }

    /** 잔액 + 최근 이력(적립·사용·복원·환불). */
    @GetMapping
    public PointView myPoints(Principal principal) {
        return pointService.myPoints(Long.parseLong(principal.getName()));
    }
}
