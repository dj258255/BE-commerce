package com.beomsu.becommerce.assist.incident;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 규칙 기준선 — <b>로그에 찍힌 말</b>로 원인을 고른다. 모델이 넘어야 할 자다.
 *
 * <p><b>왜 이게 먼저인가</b>: 잔여 후보에서 모델을 켰다가 껐다. 규칙이 이미 같은 답을 내고 있어서
 * 모델이 할 게 없었기 때문이다. 그때 놓친 것이 <b>규칙 대비로 안 봤다</b>는 것이라, 이번에는
 * 규칙을 먼저 만들고 시작한다.
 *
 * <p><b>일부러 잘 만들지 않는다.</b> 로그에 원인 이름이 그대로 찍히는 경우만 잡는다. 규칙이
 * 이것만으로 충분하면 모델이 필요 없다는 뜻이고, <b>그것도 답이다.</b>
 *
 * <p><b>규칙의 한계가 곧 모델의 자리다</b>: 이 방식은 <b>말이 안 찍히는 장애</b>를 못 잡는다.
 * 큐 적체는 "backlog" 라고 안 찍히고 <b>느려짐</b>으로 나타나며, 설정 실수는 에러를 아예 안 낸다.
 */
@Component
@ConditionalOnProperty(name = "app.assist.incident-provider", havingValue = "rule",
        matchIfMissing = true)
public class RuleBasedIncidentAnalyzer implements IncidentAnalysisPort {

    /**
     * 순서가 의미를 갖는다 — 위에서부터 먼저 맞는 것을 쓴다. 좁은 신호를 위에 둔다.
     * 넓은 신호를 위에 두면 그것이 아래를 다 먹는다.
     */
    private static final Map<IncidentCause, List<String>> SIGNS = new LinkedHashMap<>();

    static {
        SIGNS.put(IncidentCause.CERT_EXPIRY,
                List.of("certificate", "certpathvalidator", "sslhandshake", "pkix"));
        SIGNS.put(IncidentCause.RACE_CONDITION,
                List.of("deadlock", "optimisticlock", "lock wait timeout", "objectoptimistic"));
        SIGNS.put(IncidentCause.DB_TIMEOUT,
                List.of("sockettimeout", "connection is not available", "communicationslink",
                        "cannotgetjdbcconnection", "hikaripool"));
        SIGNS.put(IncidentCause.PG_UNAVAILABLE,
                List.of("resourceaccess", "circuitbreaker", "read timed out", "503", "502"));
        SIGNS.put(IncidentCause.TIME_SKEW,
                List.of("timestamp가 허용 범위", "replay"));
    }

    @Override
    public Optional<IncidentDiagnosis> diagnose(String logs) {
        if (logs == null || logs.isBlank()) {
            return Optional.empty();
        }
        String lower = logs.toLowerCase();
        for (Map.Entry<IncidentCause, List<String>> e : SIGNS.entrySet()) {
            for (String sign : e.getValue()) {
                int at = lower.indexOf(sign.toLowerCase());
                if (at >= 0) {
                    return Optional.of(new IncidentDiagnosis(e.getKey(), lineAt(logs, at), IncidentDiagnosis.Source.RULE));
                }
            }
        }
        // 아는 말이 없으면 찍지 않는다. 규칙이 못 가른 것을 규칙이 우기면 그게 제일 나쁘다.
        return Optional.empty();
    }

    /** 근거로 인용할 한 줄을 뽑는다. */
    private static String lineAt(String logs, int index) {
        int from = logs.lastIndexOf('\n', index) + 1;
        int to = logs.indexOf('\n', index);
        return logs.substring(from, to < 0 ? logs.length() : to).strip();
    }

    @Override
    public String name() {
        return "rule";
    }
}
