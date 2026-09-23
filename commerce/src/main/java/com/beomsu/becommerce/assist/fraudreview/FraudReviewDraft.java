package com.beomsu.becommerce.assist.fraudreview;

import java.util.List;

/**
 * 심사자에게 내려가는 초안.
 *
 * <p><b>{@code source} 를 함께 내리는 이유</b>: 규칙이 옮긴 문장과 모델이 쓴 문장은 신뢰도가
 * 다르다. 화면에서 구별되지 않으면 심사자가 둘을 같은 무게로 읽는다.
 * {@code assist.incident.IncidentDiagnosis#source} 와 같은 이유다.
 *
 * <p><b>{@code notes} 는 왜 떨어졌는지를 들고 다닌다.</b> 폴백이 조용히 일어나면 모델이 죽어도
 * 화면은 멀쩡해 보인다. 상황 6 의 판정 생략과 같은 종류의 문제라 같은 방식으로 드러낸다.
 *
 * @param reviewId 심사 항목 id
 * @param text     초안. 템플릿까지 실패하면 {@code null}
 * @param source   {@code template} 또는 {@code ollama:모델명}. 만들지 못했으면 {@code none}
 * @param notes    모델 초안 대신 템플릿이 나간 이유. 정상이면 비어 있다
 */
public record FraudReviewDraft(long reviewId, String text, String source, List<String> notes) {

    /** 화면에 띄울 것이 있는가. */
    public boolean present() {
        return text != null && !text.isBlank();
    }
}
