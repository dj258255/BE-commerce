package com.beomsu.becommerce.assist.draft;

import java.util.List;

/**
 * 초안 생성 결과.
 *
 * <p><b>실패도 결과로 돌려준다.</b> 예외로 던지면 화면이 "오류"만 보여주고 사람은
 * 왜 초안이 없는지 모른다. 검증에 걸린 숫자를 함께 실어야 "모델이 없는 값을 만들었다"는 걸
 * 사람이 알 수 있다.
 *
 * @param orderNo   대상 주문
 * @param text      초안 본문. 검증에 실패했으면 null
 * @param source    어느 구현이 만들었나 ({@link DraftPort#name()})
 * @param verified  {@link NumericProvenanceGuard}를 통과했나
 * @param rejected  출처에서 확인되지 않은 값들. 비어 있지 않으면 초안을 쓰지 않는다
 * @param complete  근거 타임라인이 완전했나. false면 초안도 불완전하다
 * @param jargon    초안에 남은 내부 용어들. <b>버리지 않고 표시한다</b> —
 *                  지어낸 숫자와 달리 <b>틀린 것이 아니라</b> 상담원이 고칠 수 있는 문제다.
 *                  같은 잣대로 버리면 쓸 만한 초안까지 사라진다
 * @param rubric    정답 없이 매긴 점수와 미충족 항목. 표본을 늘려도 편향이 안 들어간다
 * @param verdict   다른 계열 모델의 판정. <b>버리지 않고 표시한다</b> — 심판도 틀릴 수 있다.
 *                  지어낸 숫자(버림)와 내부 용어(표시) 사이의 세 번째 등급이다
 */
public record CsDraft(String orderNo,
                      String text,
                      String source,
                      boolean verified,
                      List<String> rejected,
                      boolean complete,
                      List<String> jargon,
                      DraftRubric.Score rubric,
                      DraftJudge.Verdict verdict) {

    static CsDraft ok(String orderNo, String text, String source, boolean complete,
                      List<String> jargon, DraftRubric.Score rubric,
                      DraftJudge.Verdict verdict) {
        return new CsDraft(orderNo, text, source, true, List.of(), complete,
                List.copyOf(jargon), rubric, verdict);
    }

    /** 검증 실패. 본문을 <b>버린다</b> — 사람이 읽으면 그 문장에 끌려간다(앵커링). */
    static CsDraft rejected(String orderNo, String source, List<String> rejected, boolean complete) {
        return new CsDraft(orderNo, null, source, false, List.copyOf(rejected), complete,
                List.of(), new DraftRubric.Score(0, 6, List.of("초안 없음 — 검증 반려"), List.of()),
                DraftJudge.Verdict.unavailable("초안이 없어 판정하지 않음"));
    }

    /** 만들 재료가 없었다. 실패와 구분한다 — 이건 정상이다. */
    static CsDraft none(String orderNo, String source, boolean complete) {
        return new CsDraft(orderNo, null, source, true, List.of(), complete,
                List.of(), new DraftRubric.Score(0, 6, List.of("초안 없음 — 사실 부족"), List.of()),
                DraftJudge.Verdict.unavailable("초안이 없어 판정하지 않음"));
    }
}
