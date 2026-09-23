package com.beomsu.becommerce.seller.screening;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("판매자 제재 스크리닝 — 애매한 것은 사람에게 보낸다")
class SellerScreeningServiceTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private SellerScreeningService serviceWith(List<SanctionsList.Entry> entries, int threshold) {
        SanctionsList list = new SanctionsList() {
            @Override public List<Entry> entries() { return entries; }
            @Override public String version() { return "test-1"; }
        };
        var svc = new SellerScreeningService(new NameMatcher(), Optional.of(list), registry);
        ReflectionTestUtils.setField(svc, "potentialThreshold", threshold);
        return svc;
    }

    private SanctionsList.Entry entry(String name) {
        return new SanctionsList.Entry(name, "TEST-PROGRAM", "XX");
    }

    @Test
    @DisplayName("정규화 후 완전히 같으면 CONFIRMED — 그래도 자동 차단은 안 한다")
    void confirmedOnExactAfterNormalization() {
        var r = serviceWith(List.of(entry("한빛무역")), 80).screen("주식회사 한빛무역");

        assertThat(r.verdict()).isEqualTo(ScreeningVerdict.CONFIRMED);
        assertThat(r.score()).isEqualTo(100);
        assertThat(r.note()).contains("사람이 확인");
    }

    @Test
    @DisplayName("임계 아래는 CLEAR")
    void clearBelowThreshold() {
        var r = serviceWith(List.of(entry("동방물산")), 80).screen("한빛무역");

        assertThat(r.verdict()).isEqualTo(ScreeningVerdict.CLEAR);
        assertThat(r.matchedEntry()).as("안 걸렸으면 무엇에 걸렸는지도 없다").isNull();
    }

    @Test
    @DisplayName("임계와 완전일치 사이는 POTENTIAL — 애매한 것을 어느 한쪽으로 밀지 않는다")
    void potentialInBetween() {
        // 한 글자 차이라 높지만 100 은 아니다
        var r = serviceWith(List.of(entry("한빛국제무역상회")), 80).screen("한빛국제무역상사");

        assertThat(r.verdict()).isEqualTo(ScreeningVerdict.POTENTIAL);
        assertThat(r.score()).isBetween(80, 99);
    }

    @Test
    @DisplayName("명단이 안 꽂혔으면 통과가 아니라 POTENTIAL — 안 본 것과 통과는 다르다")
    void treatsMissingListAsUnverified() {
        var svc = new SellerScreeningService(new NameMatcher(), Optional.empty(), registry);

        var r = svc.screen("한빛무역");

        assertThat(r.verdict())
                .as("명단이 없다고 통과시키면 아무도 대조 안 된 판매자에게 돈이 나간다")
                .isEqualTo(ScreeningVerdict.POTENTIAL);
        assertThat(r.note()).contains("명단이 없어");
    }

    @Test
    @DisplayName("여러 항목 중 가장 높은 점수를 낸다 — 하나라도 걸리면 봐야 한다")
    void picksHighestScoringEntry() {
        var r = serviceWith(List.of(entry("동방물산"), entry("한빛무역"), entry("남산상사")), 80)
                .screen("한빛무역");

        assertThat(r.matchedEntry()).isEqualTo("한빛무역");
        assertThat(r.score()).isEqualTo(100);
    }
}
