package com.beomsu.becommerce.assist.web;

import com.beomsu.becommerce.assist.residual.AutomationReadiness;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 자동 확정으로 올려도 되는 유형이 있는지 <b>재서</b> 보여준다.
 *
 * <p>조건 셋 중 <b>표본과 오류율</b>만 여기서 본다. 증거가 결정적인지와 금액이 임계 미만인지는
 * 건별이라 확정 화면에서 따로 본다.
 *
 * <p><b>여기서 켜지 않는다.</b> 판정만 낸다. 실제로 올리는 것은 코드 변경이어야 한다 —
 * 지표가 좋아졌다고 권한이 자동으로 올라가면 되돌릴 자리가 없다.
 */
@RestController
@RequestMapping("/api/v1/admin/assist")
class AutomationReadinessAdminController {

    private final AutomationReadiness readiness;

    AutomationReadinessAdminController(AutomationReadiness readiness) {
        this.readiness = readiness;
    }

    @GetMapping("/automation-readiness")
    List<AutomationReadiness.Verdict> assess() {
        return readiness.assess();
    }
}
