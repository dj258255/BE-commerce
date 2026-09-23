package com.beomsu.becommerce.assist.incident;

import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 모델이 인용한 <b>근거가 실제 로그에 있는지</b> 대조한다. 없으면 진단을 버린다.
 *
 * <p><b>왜 이게 필요한가</b>: 실측에서 모델이 큐 적체 로그를 {@code RACE_CONDITION} 으로
 * <b>찍었다</b>. 규칙은 아는 신호가 없어 기권한 자리였다. <b>틀린 답은 기권보다 나쁘다</b> —
 * 기권은 사람이 원래 하던 대로 보게 두지만, 틀린 답은 사람을 엉뚱한 곳으로 보낸다.
 *
 * <p><b>이 프로젝트가 이미 쓰는 방법이다.</b> 상담 초안에서 "초안에 나온 숫자가 사실 목록에
 * 있는가"를 대조해 하나라도 없으면 초안을 버렸다. 로그 진단도 같다 — <b>인용이 원문에 없으면
 * 그 판단은 근거가 없다.</b>
 *
 * <p><b>내용을 판정하지 않는다.</b> "이 원인이 맞느냐"를 재려 들면 그 판정이 또 틀린다.
 * 여기서 보는 것은 <b>인용이 실재하느냐</b> 하나뿐이다.
 */
@Component
public class EvidenceGroundingGuard {

    /** 인용이 이보다 짧으면 대조가 무의미하다 — 아무 로그에나 들어 있다. */
    private static final int MIN_MEANINGFUL_LENGTH = 12;

    /** 인용 중 이 비율 이상의 낱말이 로그에 있어야 한다. 모델이 말끝을 바꾸는 것까지 막지는 않는다. */
    private static final double MIN_TOKEN_HIT_RATIO = 0.6;

    /**
     * @return 인용이 로그에 실재하면 true. 진단을 그대로 쓸 수 있다는 뜻이다
     */
    public boolean grounded(IncidentDiagnosis diagnosis, String logs) {
        if (diagnosis == null || logs == null) {
            return false;
        }
        String evidence = diagnosis.evidence();
        if (evidence == null || evidence.strip().length() < MIN_MEANINGFUL_LENGTH) {
            return false;                       // 근거를 안 냈으면 못 쓴다
        }
        String haystack = logs.toLowerCase(Locale.ROOT);
        if (haystack.contains(evidence.strip().toLowerCase(Locale.ROOT))) {
            return true;                        // 그대로 인용했다
        }
        // 그대로가 아니어도, 낱말 대부분이 원문에 있으면 인용으로 본다.
        List<String> tokens = Arrays.stream(evidence.toLowerCase(Locale.ROOT).split("[\\s\\p{Punct}]+"))
                .filter(t -> t.length() >= 3)
                .toList();
        if (tokens.isEmpty()) {
            return false;
        }
        long hit = tokens.stream().filter(haystack::contains).count();
        return (double) hit / tokens.size() >= MIN_TOKEN_HIT_RATIO;
    }
}
