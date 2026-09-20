package com.beomsu.becommerce.assist.draft;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 내부 용어 감지 — 실측에서 모델이 고객용 문장에 enum 을 그대로 쓴 자리.
 *
 * <p>숫자 검증과 <b>다르게</b> 다룬다. 지어낸 숫자는 초안을 버리지만, 용어 누출은
 * 틀린 게 아니라 다듬을 문제라 표시만 한다. 이 테스트가 지키는 것은
 * <b>"세는 것이 실제로 세어지는가"</b>다 — 안 세어지면 프롬프트를 고쳐도 나아졌는지 모른다.
 */
class CustomerGlossaryTest {

    private final CustomerGlossary glossary = new CustomerGlossary();

    @Test
    @DisplayName("실측에서 실제로 샌 문장을 잡는다")
    void catchesRealLeak() {
        String draft = "대사 AMOUNT_MISMATCH로 인해 주문 상태 PAID 확인 중입니다.";
        assertThat(glossary.findJargon(draft))
                .containsExactlyInAnyOrder("AMOUNT_MISMATCH", "PAID");
    }

    @Test
    @DisplayName("사전에 없는 코드도 모양으로 잡는다 — 사전은 enum 이 늘면 뒤처진다")
    void catchesUnknownCodeByShape() {
        assertThat(glossary.findJargon("결제사 SOME_NEW_STATUS 로 처리되었습니다."))
                .containsExactly("SOME_NEW_STATUS");
    }

    @Test
    @DisplayName("깨끗한 문장은 통과한다")
    void passesCleanDraft() {
        String draft = "2026-08-30 결제 10,000원이 정상 승인되었습니다. "
                + "차액 270원은 결제 수수료로 확인 중입니다.";
        assertThat(glossary.findJargon(draft)).isEmpty();
    }

    @Test
    @DisplayName("고객 문장에 나와도 되는 약어는 봐준다")
    void allowsCommonAbbreviations() {
        assertThat(glossary.findJargon("PG사 정산 내역을 확인 중입니다.")).isEmpty();
    }

    @Test
    @DisplayName("프롬프트 사전에 바꿔 쓸 말이 실린다 — 지시 없이 검사만 하면 반려율만 오른다")
    void promptSectionCarriesReplacements() {
        String section = glossary.asPromptSection();
        assertThat(section)
                .contains("AMOUNT_MISMATCH").contains("결제사 기록과 금액이 다름")
                .contains("PAID").contains("결제 완료");
        assertThat(glossary.size()).isGreaterThanOrEqualTo(20);
    }
}
