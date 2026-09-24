package com.beomsu.becommerce.experiment;

import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/**
 * 사용자를 실험 변형에 고정 배정한다(#256).
 *
 * <p>설정: {@code app.experiments.<이름>.enabled}(기본 false) · {@code .salt}(기본 = 이름) · {@code .treatment-percent}(기본 50).
 * 버킷은 {@code SHA-256(솔트 + ":" + 사용자 id)} 의 앞 4바이트를 10,000 으로 나눈 나머지다. 실험군 비율이 p% 면 버킷 &lt; p×100 이 실험군이다.
 *
 * <p><b>왜 저장하지 않나</b>: 배정 표를 두면 쓰기가 요청마다 붙고, 표와 코드가 갈라질 수 있다. 해시는 같은 입력에 늘 같은 답을 준다.
 * 솔트를 바꾸면 모든 사용자가 다시 섞인다 — 새 실험은 새 솔트로 시작한다(앞 실험의 영향이 한쪽 변형에 몰리지 않게).
 */
@Service
public class ExperimentAssigner {

    public static final String CONTROL = "control";
    public static final String TREATMENT = "treatment";

    /** 배정 결과. 실험이 꺼져 있으면 {@code variant} 가 null 이다. */
    public record Assignment(String experiment, String variant) {

        public boolean active() {
            return variant != null;
        }

        public boolean treatment() {
            return TREATMENT.equals(variant);
        }
    }

    private final Environment env;
    private final MeterRegistry registry;

    public ExperimentAssigner(Environment env, MeterRegistry registry) {
        this.env = env;
        this.registry = registry;
    }

    public Assignment assign(String experiment, long userId) {
        String prefix = "app.experiments." + experiment;
        if (!env.getProperty(prefix + ".enabled", Boolean.class, false)) {
            return new Assignment(experiment, null);
        }
        String salt = env.getProperty(prefix + ".salt", experiment);
        int treatmentPercent = Math.max(0, Math.min(100, env.getProperty(prefix + ".treatment-percent", Integer.class, 50)));
        String variant = bucket(salt, userId) < treatmentPercent * 100 ? TREATMENT : CONTROL;
        registry.counter("experiment.assignment", "experiment", experiment, "variant", variant).increment();
        return new Assignment(experiment, variant);
    }

    /** 0 ~ 9,999. 같은 솔트·사용자면 늘 같다. */
    static int bucket(String salt, long userId) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest((salt + ":" + userId).getBytes(StandardCharsets.UTF_8));
            long v = ((h[0] & 0xFFL) << 24) | ((h[1] & 0xFFL) << 16) | ((h[2] & 0xFFL) << 8) | (h[3] & 0xFFL);
            return (int) (v % 10_000);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
