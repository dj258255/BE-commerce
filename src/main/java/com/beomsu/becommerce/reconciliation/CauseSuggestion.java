package com.beomsu.becommerce.reconciliation;

import java.util.Set;

/**
 * 불일치 원인 후보 하나 (ADR-012).
 *
 * @param cause      제안하는 원인
 * @param confidence 확신 수준. <b>이것만으로 자동 확정을 정당화하지 않는다</b> —
 *                   자동화 권한은 유형·금액·실측 오류율과 함께 판단한다
 * @param evidence   왜 그렇게 판단했는지. <b>사람이 검증할 수 있어야 한다</b> —
 *                   근거 없는 제안은 확인 비용만 늘린다
 * @param figures    이 규칙이 <b>계산한</b> 금액들(원). {@code evidence} 문장에 등장하는 값과 같지만,
 *                   <b>문장을 파싱해서 얻는 게 아니라 규칙이 직접 넣는다.</b> 산문을 파싱하면
 *                   근거 문구를 고칠 때 이 목록이 조용히 바뀐다. 초안 검증기가 "코드가 낸 숫자"를
 *                   가릴 때 쓰는 원천이라, 문구와 값을 분리해 둔다
 */
public record CauseSuggestion(ResolveCause cause, Confidence confidence, String evidence,
                              Set<Long> figures) {

    public CauseSuggestion {
        figures = figures == null ? Set.of() : Set.copyOf(figures);
    }

    /**
     * 확신 수준.
     *
     * <p><b>DECISIVE</b>는 "증거가 정확히 맞아떨어져 다른 해석이 없다"는 뜻이지
     * "자동으로 확정해도 된다"는 뜻이 아니다. 그 판단은 별개다.
     */
    public enum Confidence {
        /** 산수가 정확히 맞는다. 예: 차액이 수수료율과 원 단위까지 일치 */
        DECISIVE,
        /** 정황이 맞지만 다른 설명도 가능하다 */
        LIKELY,
        /** 배제법으로 남은 것 */
        WEAK
    }

    public static CauseSuggestion decisive(ResolveCause cause, String evidence, Long... figures) {
        return new CauseSuggestion(cause, Confidence.DECISIVE, evidence, nonNull(figures));
    }

    public static CauseSuggestion likely(ResolveCause cause, String evidence, Long... figures) {
        return new CauseSuggestion(cause, Confidence.LIKELY, evidence, nonNull(figures));
    }

    private static Set<Long> nonNull(Long... figures) {
        Set<Long> out = new java.util.LinkedHashSet<>();
        for (Long f : figures) {
            if (f != null) {
                out.add(Math.abs(f));
            }
        }
        return out;
    }
}
