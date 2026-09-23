package com.beomsu.becommerce.seller;

import com.beomsu.becommerce.seller.screening.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 등록 → 대조 → 판정 기록 → <b>지급 보류</b>까지 실제로 도는지 실 MySQL 로 본다.
 *
 * <p><b>왜 실 DB 인가</b>: 이 저장소는 목으로만 검증했다가 실 DB 에서 셋이 드러난 적이 있다
 * (rollback-only 트랜잭션, {@code readOnly} 가 FlushMode 를 바꾸는 것, ENUM 잘림).
 * 상태 전이와 새 컬럼이 걸린 자리라 같은 함정이 열려 있다.
 *
 * <p>명단은 <b>고정한 것</b>을 쓴다. 실제 UN 명단은 갱신되므로 그것으로 판정을 검사하면
 * 어느 날 조용히 깨진다 — 실 명단으로 파서를 검증하는 것은
 * {@code UnSanctionsListLiveTest} 가 따로 한다.
 */
@Tag("integration")
@SpringBootTest
class SellerScreeningEndToEndMySqlTest {

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4").withDatabaseName("screening");

    static { MYSQL.start(); }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", MYSQL::getJdbcUrl);
        r.add("spring.datasource.username", MYSQL::getUsername);
        r.add("spring.datasource.password", MYSQL::getPassword);
        r.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    /** 판정이 흔들리지 않게 명단을 고정한다. */
    @TestConfiguration
    static class FixedList {
        @Bean @Primary
        SanctionsList fixed() {
            return new SanctionsList() {
                @Override public List<Entry> entries() {
                    return List.of(
                            new Entry("KIM CHUL SOO", "DPRK", "KP", "1980"),
                            new Entry("DONGBANG TRADING CO", "DPRK", "KP", null));
                }
                @Override public String version() { return "TEST-2026-09-06"; }
            };
        }
    }

    @Autowired SellerOnboardingService onboarding;
    @Autowired SellerRepository sellers;
    @Autowired SellerScreeningRepository screenings;
    @Autowired SellerPayoutGate gate;

    @Test
    @DisplayName("깨끗한 판매자는 등록 즉시 지급 가능해진다")
    void cleanSellerBecomesPayable() {
        var s = onboarding.register("111-11-11111", "한빛무역", "박범수", "KR", LocalDate.of(1996, 5, 2));

        assertThat(sellers.findById(s.getId()).orElseThrow().getStatus())
                .as("명단에 없으면 통과다").isEqualTo(SellerStatus.ACTIVE);
        assertThat(gate.check(s.getId()).allowed()).isTrue();
        assertThat(screenings.findBySellerIdOrderByScreenedAtDesc(s.getId()))
                .as("법인명과 대표자명을 따로 대조해 두 행이 남는다").hasSize(2)
                .allSatisfy(r -> assertThat(r.getListVersion())
                        .as("어느 판과 대조했는지 남겨야 '그때는 통과였다'를 댈 수 있다")
                        .isEqualTo("TEST-2026-09-06"));
    }

    @Test
    @DisplayName("명단에 걸린 판매자는 지급이 막힌다")
    void matchedSellerIsBlockedFromPayout() {
        var s = onboarding.register("222-22-22222", "동방물산", "KIM CHUL SOO", "KP", LocalDate.of(1980, 3, 15));

        var saved = sellers.findById(s.getId()).orElseThrow();
        assertThat(saved.getStatus()).isNotEqualTo(SellerStatus.ACTIVE);

        var decision = gate.check(s.getId());
        assertThat(decision.allowed()).as("걸린 판매자에게 돈이 나가면 안 된다").isFalse();
        assertThat(decision.reason()).contains(saved.getStatus().name());
    }

    @Test
    @DisplayName("모르는 판매자에게는 돈을 안 내보낸다 — 안 본 것과 통과는 다르다")
    void unknownSellerIsBlocked() {
        assertThat(gate.check(999_999L).allowed()).isFalse();
    }

    @Test
    @DisplayName("플랫폼 직판은 외부로 나가는 지급이 아니라 통과한다")
    void platformDirectSaleIsAllowed() {
        assertThat(gate.check(SellerPayoutGate.PLATFORM_SELLER_ID).allowed()).isTrue();
    }

    @Test
    @DisplayName("사람이 판정한 것은 두 번 안 덮는다 — 덮으면 오탐률이 조용히 바뀐다")
    void humanVerdictIsWriteOnce() {
        var s = onboarding.register("333-33-33333", "동방물산", "KIM CHUL SOO", "KP", LocalDate.of(1980, 3, 15));
        var rec = screenings.findBySellerIdOrderByScreenedAtDesc(s.getId()).getFirst();

        rec.review(SellerScreening.HumanVerdict.FALSE_POSITIVE, "beomsu");
        screenings.saveAndFlush(rec);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        rec.review(SellerScreening.HumanVerdict.TRUE_POSITIVE, "someone"))
                .isInstanceOf(IllegalStateException.class);
    }
}
