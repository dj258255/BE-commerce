package com.beomsu.becommerce;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 배치 스케줄러의 <b>게이트 짝</b>을 구조로 강제한다.
 *
 * <p>이 프로젝트의 배치는 전부 기본 off이고, 켜려면 <b>두 가지</b>가 같은 프로퍼티로 함께 켜져야 한다.
 * <ol>
 *   <li>{@code @Scheduled}를 가진 스케줄러 빈 — {@code @ConditionalOnProperty}로 등록되고</li>
 *   <li>짝이 되는 {@code @EnableScheduling} 설정 — <b>같은</b> 프로퍼티로 스케줄링 자체를 켠다</li>
 * </ol>
 *
 * <p><b>둘 중 하나만 켜지면 조용히 아무 일도 일어나지 않는다.</b> 빈은 정상 등록되고, 기동 로그도
 * 깨끗하고, {@code @Scheduled} 메서드만 영원히 안 불린다. 정산 배치를 켠 줄 알았는데 정산이 안 되고
 * 있는 상태를, 며칠 뒤 대사에서야 알게 된다.
 *
 * <p>지금 10쌍이 전부 올바르게 짝지어져 있다. 다만 그걸 지켜주는 건 각 클래스의 javadoc뿐이라,
 * 스케줄러를 하나 더 추가하거나 프로퍼티 이름을 바꾸는 순간 조용히 어긋날 수 있다. 이 테스트가
 * 그 순간에 깨진다.
 *
 * <p>이 프로젝트에서 <b>"있는지"만 보고 "불리는지"를 안 본</b> 결함을 세 번 만났다 — 감사 로그
 * 서비스에 호출부가 없었고, 비밀번호 이관 서비스가 배선 확인 없이 단위 테스트만 있었고, 결제사
 * 라우팅도 켰을 때 꽂히는지 검증이 없었다. 셋 다 개별 테스트로 막았지만, 배치 10개는 개별로
 * 막기엔 수가 많다. 그래서 규칙 자체를 검사한다.
 */
class SchedulerGatePairingTest {

    private static final String BASE_PACKAGE = "com.beomsu.becommerce";

    @Test
    @DisplayName("@Scheduled를 가진 모든 스케줄러는 같은 프로퍼티로 켜지는 @EnableScheduling 짝을 가진다")
    void everySchedulerHasMatchingEnableSchedulingGate() {
        Map<String, String> schedulerGates = scan(SchedulerGatePairingTest::hasScheduledMethod);
        Set<String> enableSchedulingGates = new LinkedHashSet<>(
                scan(m -> m.hasAnnotation(EnableScheduling.class.getName())).values());

        // 스케줄러가 아예 안 잡히면 스캔이 고장난 것 — 통과로 오인하지 않게 하한을 둔다.
        assertThat(schedulerGates)
                .as("@Scheduled 스케줄러를 찾지 못했다면 이 테스트는 아무것도 검증하지 못한다")
                .hasSizeGreaterThanOrEqualTo(10);

        assertThat(schedulerGates)
                .allSatisfy((scheduler, gate) -> assertThat(gate)
                        .as("%s는 '%s'로 켜지는데, 같은 프로퍼티의 @EnableScheduling 설정이 없다. "
                            + "빈은 등록되지만 @Scheduled가 영원히 안 불린다", scheduler, gate)
                        .isIn(enableSchedulingGates));
    }

    @Test
    @DisplayName("스케줄러는 모두 프로퍼티 게이트를 가진다 — 게이트 없이 항상 도는 배치가 없어야 한다")
    void everySchedulerIsGated() {
        // 게이트 없는 스케줄러는 테스트·로컬 부팅에서도 돌아 부작용을 낸다(그래서 전부 기본 off다).
        assertThat(scan(SchedulerGatePairingTest::hasScheduledMethod))
                .allSatisfy((scheduler, gate) -> assertThat(gate)
                        .as("%s에 @ConditionalOnProperty 게이트가 없다", scheduler)
                        .isNotEqualTo(NO_GATE));
    }

    private static final String NO_GATE = "(게이트 없음)";

    /**
     * 조건에 맞는 클래스를 찾아 {클래스 단순명 → 게이트 프로퍼티}로 만든다.
     *
     * <p><b>{@code ClassPathScanningCandidateComponentProvider}를 쓰면 안 된다.</b> 그건 {@code @Conditional}을
     * <b>평가</b>해서, 꺼져 있는 빈을 후보에서 빼버린다. 이 프로젝트의 스케줄러는 전부 기본 off라
     * 하나도 안 잡히고, 그러면 빈 집합에 대해 검사가 전부 통과해 <b>초록불인데 아무것도 검증하지 않는
     * 테스트</b>가 된다(실제로 처음에 그렇게 짰다가 아래 하한 단언에 걸려 알았다).
     *
     * <p>그래서 클래스를 로드하지도, 조건을 평가하지도 않고 바이트코드의 애너테이션 메타데이터만 읽는다.
     */
    private static Map<String, String> scan(Predicate<AnnotationMetadata> match) {
        Map<String, String> found = new LinkedHashMap<>();
        var resolver = new PathMatchingResourcePatternResolver();
        var factory = new CachingMetadataReaderFactory(resolver);
        try {
            for (var resource : resolver.getResources(
                    "classpath*:" + BASE_PACKAGE.replace('.', '/') + "/**/*.class")) {
                AnnotationMetadata metadata = factory.getMetadataReader(resource).getAnnotationMetadata();
                if (match.test(metadata)) {
                    found.put(simpleName(metadata.getClassName()), gateOf(metadata));
                }
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("클래스패스 스캔 실패", e);
        }
        return found;
    }

    private static boolean hasScheduledMethod(AnnotationMetadata metadata) {
        return metadata.hasAnnotatedMethods(Scheduled.class.getName());
    }

    private static String simpleName(String className) {
        return className.substring(className.lastIndexOf('.') + 1);
    }

    /** {@code @ConditionalOnProperty}의 프로퍼티 이름. name과 value 중 쓰인 쪽을 본다. */
    private static String gateOf(AnnotationMetadata metadata) {
        var attrs = metadata.getAnnotationAttributes(ConditionalOnProperty.class.getName());
        if (attrs == null) return NO_GATE;
        for (String key : new String[]{"name", "value"}) {
            String[] names = (String[]) attrs.get(key);
            if (names != null && names.length > 0) return names[0];
        }
        return NO_GATE;
    }
}
