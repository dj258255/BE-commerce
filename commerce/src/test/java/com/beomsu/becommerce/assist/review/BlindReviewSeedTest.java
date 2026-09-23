package com.beomsu.becommerce.assist.review;

import com.beomsu.becommerce.reconciliation.ReconciliationAdminService;
import org.springframework.data.domain.PageRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 블라인드 리뷰 표본을 <b>초안만 고정한 상태로</b> 심는다.
 *
 * <p><b>왜 필요한가</b>: 초안을 그 자리에서 만들면 건당 10초 안팎이 든다. 12건이면 답을
 * 제출할 때마다 그만큼 기다리고, <b>그 대기가 표본이 0건이던 실제 이유</b>였다. 쌍 비교에서
 * 이미 겪어 {@code NarrativeComparisonSeedTest} 로 갈라 뒀고, 여기서도 같게 한다.
 *
 * <p><b>블라인드 답은 안 채운다.</b> {@code blind_reply} 를 비운 채로 넣으므로 화면은
 * 여전히 1단계부터 시작하고, 초안은 답을 제출하기 전까지 응답에 실리지 않는다. 공개 여부를
 * 초안 존재가 아니라 {@code revealed_at} 으로 가르기 때문에 심어도 전제가 안 깨진다.
 *
 * <p>A/B 배정도 심을 때 정해져 고정된다. 나중에 다시 뽑으면 사람이 본 것과 다른 문장을
 * 채점하게 된다.
 */
@Tag("capture")
@SpringBootTest
@DisplayName("블라인드 리뷰 표본 심기 — 만드는 일과 쓰는 일을 가른다")
class BlindReviewSeedTest {

    @Autowired BlindReviewService service;
    // 모듈 경계를 넘지 않는다. `reconciliation.internal` 을 직접 읽으면 이 저장소의
    // 규칙 테스트에 걸리고, 걸리는 게 맞다. 공개 API 로 받는다.
    @Autowired ReconciliationAdminService reconciliation;

    @Test
    @DisplayName("미해결 대사 건에 초안 둘을 고정해 둔다")
    void seed() {
        String reviewer = System.getProperty("seed.reviewer", "admin");
        int want = Integer.getInteger("seed.count", 12);

        // 실제 미해결 대사 건에서 뽑는다. 합성 표본을 심으면 사람이 보는 사실이 운영과 달라진다.
        var targets = reconciliation.listMismatches(PageRequest.of(0, want))
                .stream()
                .filter(v -> v.orderNo() != null)
                .toList();

        if (targets.isEmpty()) {
            System.out.println("심을 대사 결과가 없다. 먼저 대사를 돌려 불일치를 만들어야 한다.");
            return;
        }

        int seeded = 0;
        for (var v : targets) {
            seeded += service.preloadAll(v.id(), v.orderNo(), reviewer);
        }

        System.out.println("심은 표본 " + seeded + "건 (대상 " + targets.size() + "건)");
        System.out.println("  전부 <초안만 고정> 상태다. 블라인드 답은 비어 있다.");
        System.out.println("  앱을 띄우고 /admin.html 의 블라인드 리뷰 패널에서 쓰면 된다.");

        assertThat(targets).as("표본이 하나는 있어야 리뷰를 시작한다").isNotEmpty();
    }

    @Test
    @DisplayName("심은 표본은 블라인드 답 전까지 공개 상태가 아니다")
    void seededSamplesStayBlind() {
        String reviewer = System.getProperty("seed.reviewer", "admin");

        // <새 리뷰어로 start 하지 않는다.> 그러면 확인하려고 부른 것이 표본에 행을 남긴다.
        // 방금 심은 그 행들을 그대로 본다.
        List<String> stages = reconciliation.listMismatches(PageRequest.of(0, 3))
                .stream()
                .filter(v -> v.orderNo() != null)
                .map(v -> service.start(v.id(), v.orderNo(), reviewer).stage())
                .toList();

        assertThat(stages)
                .as("심어도 1단계부터 시작해야 한다. 아니면 표본이 조용히 오염된다")
                .allMatch(s -> s.equals("BLIND"));
    }
}
