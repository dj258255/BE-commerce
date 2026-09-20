package com.beomsu.becommerce;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * 모듈 경계 검증 + 문서 생성.
 *
 * <p>{@link #verifiesModularStructure()}는 순환 의존, 허용되지 않은 모듈 접근,
 * 내부 구현 패키지로의 침투를 정적으로 검사한다 — 아키텍처 규칙을 테스트로 강제한다.
 * {@link #writeDocumentation()}는 모듈 다이어그램(PlantUML)과 모듈 문서를
 * {@code build/spring-modulith-docs} 에 생성한다.
 */
class ModularityTests {

    static final ApplicationModules modules = ApplicationModules.of(BeCommerceApplication.class);

    @Test
    void verifiesModularStructure() {
        modules.verify();
    }

    @Test
    void writeDocumentation() {
        new Documenter(modules)
                .writeModulesAsPlantUml()
                .writeIndividualModulesAsPlantUml();
    }
}
