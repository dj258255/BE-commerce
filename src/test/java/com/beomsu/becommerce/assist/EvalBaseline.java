package com.beomsu.becommerce.assist;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 평가 수치의 <b>기준선</b>. 켤 때 잰 값을 파일로 남기고, 다시 잴 때 그것과 견준다.
 *
 * <p><b>왜 필요한가</b>: 이 프로젝트의 평가는 <b>시점 측정</b>이었다. 켤 때 재고 문서에 적고
 * 끝이다. 그런데 모델이 바뀌거나 프롬프트가 바뀌거나 ollama 가 올라가면 그 수치는 더 이상
 * 사실이 아니고, <b>나빠져도 아무도 모른다.</b>
 *
 * <p>업계에서 LLM 프로덕션의 가장 어려운 부분으로 꼽는 것이 정확히 이것이다. 좋은 데모를
 * 만드는 것이 아니라 <b>실제 데이터가 들어온 뒤에도 품질을 유지하는 것</b>이고, 평가가
 * 감으로 이뤄지는 것이 그 원인으로 지목된다.
 *
 * <p><b>절대 수치를 통과 조건으로 걸지 않는다.</b> 그건 처음부터 의도한 것이고 지금도 맞다 —
 * 모델 정확도에 임계를 걸면 표본이 바뀔 때마다 테스트가 깨진다. 대신 <b>회귀</b>를 본다.
 * 어제보다 나빠졌으면 그건 표본 탓이 아니라 무언가 바뀐 것이다.
 *
 * <p>기준선이 없으면 <b>만들고 통과시킨다.</b> 첫 실행에서 깨뜨리면 아무도 안 돌린다.
 */
public final class EvalBaseline {

    private static final Path DIR = Path.of("src/test/resources/eval-baselines");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 이 값만큼 나빠지는 것은 모델의 흔들림으로 보고 넘긴다. 그 이상은 회귀다. */
    private static final double TOLERANCE = 0.05;

    private EvalBaseline() {
    }

    /**
     * 기준선과 견준다. 없으면 만들고 통과시킨다.
     *
     * @param name    평가 이름. 파일 하나가 된다
     * @param metrics 이름 → 값. <b>클수록 좋은 값만</b> 넣는다(맞은 수, 정확도 등)
     * @return 회귀한 지표의 설명. 비어 있으면 회귀 없음
     */
    public static String compare(String name, Map<String, Double> metrics) {
        return compare(name, metrics, Map.of());
    }

    /**
     * @param context 무엇이 이 수치를 냈는지. 모델 이름·표본 수·프롬프트 지문 같은 것.
     *                <b>회귀가 났을 때 무엇이 바뀌었는지 짚으려면 이게 있어야 한다.</b>
     *                수치만 남기면 "나빠졌다"까지만 알고 왜인지는 매번 다시 조사하게 된다
     */
    public static String compare(String name, Map<String, Double> metrics, Map<String, String> context) {
        Path file = DIR.resolve(name + ".json");
        try {
            if (!Files.exists(file)) {
                write(file, metrics, context);
                return "";   // 첫 실행 — 기준선을 만들고 통과시킨다
            }
            Map<?, ?> saved = MAPPER.readValue(Files.readString(file), Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> before = (Map<String, Object>) saved.get("metrics");

            @SuppressWarnings("unchecked")
            Map<String, Object> beforeCtx = (Map<String, Object>) saved.get("context");

            StringBuilder regressed = new StringBuilder();
            for (var e : metrics.entrySet()) {
                Object old = before == null ? null : before.get(e.getKey());
                if (old == null) {
                    continue;   // 새로 생긴 지표는 견줄 것이 없다
                }
                double was = ((Number) old).doubleValue();
                double now = e.getValue();
                // 절대값이 아니라 <b>떨어진 폭</b>을 본다.
                if (now < was - Math.max(TOLERANCE, Math.abs(was) * TOLERANCE)) {
                    regressed.append("    %s: %.3f → %.3f%n".formatted(e.getKey(), was, now));
                }
            }
            if (regressed.isEmpty()) {
                return "";
            }
            // 무엇이 바뀌었는지 같이 낸다. 이게 없으면 "나빠졌다"까지만 알고
            // 원인은 매번 처음부터 조사하게 된다.
            for (var e : context.entrySet()) {
                Object was = beforeCtx == null ? null : beforeCtx.get(e.getKey());
                if (was != null && !was.equals(e.getValue())) {
                    regressed.append("    (바뀜) %s: %s → %s%n".formatted(e.getKey(), was, e.getValue()));
                }
            }
            return regressed.toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 기준선을 새 값으로 덮는다. <b>회귀를 확인하고 받아들이기로 했을 때만</b> 부른다. */
    public static void accept(String name, Map<String, Double> metrics, Map<String, String> context) {
        try {
            write(DIR.resolve(name + ".json"), metrics, context);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void write(Path file, Map<String, Double> metrics,
                              Map<String, String> context) throws IOException {
        Files.createDirectories(file.getParent());
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("recordedAt", Instant.now().toString());
        doc.put("note", "회귀 감지용 기준선. 나빠지면 evalTest 가 알린다. "
                + "바뀐 것이 맞다고 판단하면 EvalBaseline.accept 로 갱신한다");
        doc.put("context", context);
        doc.put("metrics", metrics);
        Files.writeString(file, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(doc));
    }
}
