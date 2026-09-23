package com.beomsu.becommerce.assist.incident;

/**
 * 진단 하나.
 *
 * @param cause    고른 원인 유형
 * @param evidence <b>로그에서 인용한 한 줄.</b> 인용을 강제하는 이유는, 근거 없이 유형만 내면
 *                 사람이 그 판단을 확인할 방법이 없어서다. 상담 초안에서 숫자 출처를 강제한 것과 같은 결
 * @param source   <b>누가 낸 답인가.</b> 실제 로그 12건으로 재니 규칙은 틀림 0·기권 5였고 모델은
 *                 기권 0·틀림 1이었다. 정확도가 아니라 <b>틀리는 방식</b>이 달라서, 화면에서 둘을
 *                 같은 무게로 보여 주면 안 된다
 */
public record IncidentDiagnosis(IncidentCause cause, String evidence, Source source) {

    /** 규칙이 낸 답인지 모델이 낸 답인지. */
    public enum Source {
        /** 규칙이 냈다. 아는 것만 답하므로 틀린 적이 없다. */
        RULE,
        /** 모델이 냈다. 규칙이 기권한 자리에만 나오며, <b>사람이 확정하기 전에는 후보일 뿐이다.</b> */
        MODEL
    }

    /** 출처를 안 밝히면 모델로 본다 — 덜 믿는 쪽이 기본값이어야 한다. */
    public IncidentDiagnosis(IncidentCause cause, String evidence) {
        this(cause, evidence, Source.MODEL);
    }
}
