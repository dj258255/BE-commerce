package com.beomsu.becommerce.testsupport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;

/**
 * 건너뛴 테스트와 그 이유를 파일에 적는다(#278). {@code test.skip-reasons.file} 이 있을 때만 적는다.
 *
 * <p>Gradle 의 JUnit XML 은 조건으로 건너뛴 테스트의 이유를 남기지 않는다({@code <skipped/>} 가 빈다). 그러면
 * {@code tools/check_skipped_tests.py} 가 "Spring Modulith 가 변경 영향이 없어 건너뛴 것"과 "조용히 빠진 것"을 가를 수 없다.
 * 한 줄에 {@code 클래스[#메서드]<탭>이유}.
 */
public class SkipReasonRecorder implements TestExecutionListener {

    private static final String PROPERTY = "test.skip-reasons.file";

    @Override
    public void executionSkipped(TestIdentifier id, String reason) {
        String file = System.getProperty(PROPERTY);
        if (file == null || file.isBlank()) {
            return;
        }
        String target = id.getSource().map(SkipReasonRecorder::name).orElse(id.getDisplayName());
        String line = target + "\t" + (reason == null ? "" : reason.replace('\n', ' ')) + "\n";
        try {
            Path path = Path.of(file);
            Files.createDirectories(path.getParent());
            Files.writeString(path, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException("건너뛴 이유를 적지 못했다: " + file, e);
        }
    }

    private static String name(TestSource source) {
        if (source instanceof MethodSource m) {
            return m.getClassName() + "#" + m.getMethodName();
        }
        if (source instanceof ClassSource c) {
            return c.getClassName();
        }
        return source.toString();
    }
}
