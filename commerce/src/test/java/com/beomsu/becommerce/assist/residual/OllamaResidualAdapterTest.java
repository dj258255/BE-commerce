package com.beomsu.becommerce.assist.residual;

import com.beomsu.becommerce.assist.draft.FactPack;
import com.beomsu.becommerce.reconciliation.ResolveCause;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 응답 파싱 = 가드 2(출력 화이트리스트)가 실제로 서는 자리.
 *
 * <p>모델은 형식을 자주 어긴다. 프롬프트에 "목록 안의 값만"이라고 적어도 그대로
 * 지키지 않는다. 그래서 <b>변환되지 않으면 버린다</b>를 여기서 고정한다.
 */
class OllamaResidualAdapterTest {

    private static final FactPack FACTS = new FactPack("ORD-1",
            List.of("2026-08-30 · PAYMENT · 결제 승인"),
            Set.of(100_000L), Set.of(LocalDate.of(2026, 8, 30)), null, true);

    private Optional<ResidualSuggestion> parse(String text) {
        return OllamaResidualAdapter.parse(text, FACTS);
    }

    @Test
    @DisplayName("형식을 지킨 응답은 그대로 읽는다")
    void parsesWellFormed() {
        Optional<ResidualSuggestion> out = parse("""
                CAUSE: PG_FILE_DELAY
                CONFIDENCE: 82
                RATIONALE: 다음 거래일 파일에 같은 주문이 있습니다.
                """);

        assertThat(out).isPresent();
        assertThat(out.get().cause()).isEqualTo(ResolveCause.PG_FILE_DELAY);
        assertThat(out.get().confidence()).isEqualTo(82);
        assertThat(out.get().rationale()).contains("다음 거래일 파일");
    }

    @Test
    @DisplayName("ABSTAIN 은 기권으로 읽는다")
    void parsesAbstain() {
        assertThat(parse("""
                CAUSE: ABSTAIN
                CONFIDENCE: 20
                RATIONALE: 근거가 부족합니다.
                """)).isEmpty();
    }

    @Test
    @DisplayName("가드 2 — 목록에 없는 원인을 지어내면 버린다")
    void rejectsInventedCause() {
        assertThat(parse("""
                CAUSE: BANK_HOLIDAY_SHIFT
                CONFIDENCE: 95
                RATIONALE: 은행 휴일 때문입니다.
                """)).isEmpty();
    }

    @Test
    @DisplayName("한 줄이라도 빠지면 버린다")
    void rejectsIncomplete() {
        assertThat(parse("CAUSE: PG_FILE_DELAY\nRATIONALE: 늦게 왔습니다.")).isEmpty();
        assertThat(parse("CONFIDENCE: 90\nRATIONALE: 늦게 왔습니다.")).isEmpty();
    }

    @Test
    @DisplayName("설명을 덧붙여도 세 줄만 뽑아낸다")
    void toleratesChatter() {
        Optional<ResidualSuggestion> out = parse("""
                생각해보겠습니다. 이 건은 타임존 경계로 보입니다.

                CAUSE: TIMEZONE_BOUNDARY
                CONFIDENCE: 75
                RATIONALE: 인접일에 같은 주문번호가 있습니다.

                도움이 되었길 바랍니다.
                """);

        assertThat(out).isPresent();
        assertThat(out.get().cause()).isEqualTo(ResolveCause.TIMEZONE_BOUNDARY);
    }

    @Test
    @DisplayName("범위를 벗어난 신뢰도는 잘라서 받는다")
    void clampsConfidence() {
        assertThat(parse("CAUSE: PG_FILE_DELAY\nCONFIDENCE: 150\nRATIONALE: 늦었습니다.")
                .orElseThrow().confidence()).isEqualTo(100);
    }
}
