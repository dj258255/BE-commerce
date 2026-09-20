package com.beomsu.becommerce.assist.web;

import com.beomsu.becommerce.assist.narrative.NarrativeComparisonService;
import com.beomsu.becommerce.assist.narrative.TimelineNarrativeService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.Map;
import org.springframework.web.bind.annotation.RestController;

/**
 * 운영자용 서술 창구. <b>읽기 전용</b>이다.
 *
 * <p>어드민 화면이 20개인데 "이 주문에 무슨 일이 있었나"를 한 번에 묻는 곳이 없었다.
 * 조회는 상황 13에서 만들었고, 여기서는 그 결과를 <b>문장</b>으로 돌려준다.
 *
 * <p>못 만들면 <b>204</b>다. 빈 문자열이나 "생성 실패" 같은 문장을 돌려주면 화면이 그걸
 * 서술로 띄운다. 없으면 없다고 해야 화면이 원래 목록만 보여준다.
 */
@RestController
@RequestMapping("/api/v1/admin/orders")
class TimelineNarrativeAdminController {

    private final TimelineNarrativeService service;
    private final NarrativeComparisonService comparison;

    TimelineNarrativeAdminController(TimelineNarrativeService service,
                                     NarrativeComparisonService comparison) {
        this.service = service;
        this.comparison = comparison;
    }

    @GetMapping("/{orderNo}/narrative")
    ResponseEntity<TimelineNarrativeService.Narrative> narrative(@PathVariable String orderNo) {
        return service.narrate(orderNo)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * 같은 사실에 대한 두 서술을 <b>출처를 가린 채</b> 내놓는다. 어느 쪽이 모델인지 안 알려준다.
     * 비교할 구현이 둘 미만이거나 어느 한쪽이 못 만들면 204.
     */
    @PostMapping("/{orderNo}/narrative/compare")
    ResponseEntity<NarrativeComparisonService.Comparison> compare(@PathVariable String orderNo) {
        return comparison.open(orderNo)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** 고른다. 고르고 나서야 어느 쪽이 무엇이었는지 공개된다. */
    @PostMapping("/narrative/compare/{id}")
    ResponseEntity<NarrativeComparisonService.Revealed> choose(
            @PathVariable long id,
            @RequestBody ChoiceRequest request,
            @RequestHeader(value = "X-Admin-Id", required = false) String reviewer) {
        return comparison.choose(id, request.choice(), reviewer)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 미리 만들어 둔 것 중 아직 안 고른 비교 하나. 없으면 204.
     *
     * <p>고르는 사람은 이 문만 두드리면 된다. 만드는 일({@code POST .../compare})과 갈라 둔 이유는
     * 모델 호출이 건당 10초 안팎이라 클릭 사이의 대기가 그대로 사람 시간이 되기 때문이다.
     */
    @GetMapping("/narrative/compare/next")
    ResponseEntity<NarrativeComparisonService.Comparison> next() {
        return comparison.nextPending()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** 아직 안 고른 비교가 몇 건 남았나. */
    @GetMapping("/narrative/compare/pending")
    Map<String, Long> pending() {
        return Map.of("pending", comparison.pendingCount());
    }

    /** 고른 것을 CSV 로 받는다. 로컬 DB 는 지워지므로 판정은 파일로 꺼내 둔다. */
    @GetMapping(value = "/narrative/compare/export", produces = "text/csv;charset=UTF-8")
    ResponseEntity<String> export() {
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"narrative-preferences.csv\"")
                .body(comparison.exportCsv());
    }

    /** 집계 — 무엇이 몇 번 선택됐나. <b>이 표가 쌓인 다음에</b> 기본값을 정한다. */
    @GetMapping("/narrative/compare/stats")
    NarrativeComparisonService.Stats stats() {
        return comparison.stats();
    }

    record ChoiceRequest(String choice) {
    }
}
