package com.beomsu.becommerce.reconciliation.integrity;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 정합성 점검을 지금 돌려 본다(#389). 인가는 {@code /api/v1/admin/**} 의 ROLE_ADMIN.
 *
 * <p>{@code graceSeconds} 를 바꿔 부를 수 있게 둔 것은 유예를 재기 위해서다. 같은 순간에 유예만 달리 세면 유예가 짧을 때
 * 진행 중인 건을 몇 개나 잘못 세는지 보인다.
 */
@RestController
@RequestMapping("/api/v1/admin/integrity")
class IntegrityAdminController {

    private final IntegrityCheckService service;

    IntegrityAdminController(IntegrityCheckService service) {
        this.service = service;
    }

    @GetMapping
    Map<String, Object> check(@RequestParam(name = "graceSeconds", required = false) Long graceSeconds,
                              @RequestParam(name = "samples", defaultValue = "0") int samples) {
        Duration grace = graceSeconds == null ? service.defaultGrace() : Duration.ofSeconds(Math.max(0, graceSeconds));
        Map<Invariant, Long> counts = service.count(grace);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("graceSeconds", grace.toSeconds());
        out.put("total", counts.values().stream().mapToLong(Long::longValue).sum());
        Map<String, Object> byInvariant = new LinkedHashMap<>();
        counts.forEach((inv, n) -> byInvariant.put(inv.name(), samples > 0 && n > 0
                ? Map.of("count", n, "samples", service.samples(inv, grace, samples))
                : Map.of("count", n)));
        out.put("invariants", byInvariant);
        return out;
    }
}
