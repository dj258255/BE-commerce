package com.beomsu.becommerce;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 저장소 루트를 찾아 준다 — <b>테스트가 자기 위치를 문자열로 알고 있지 않게</b> 하려는 것이다.
 *
 * <p>이 Gradle 프로젝트의 루트는 {@code commerce/}이고, 테스트의 작업 디렉터리도 거기다. 그런데
 * {@code docs/}·{@code monitoring/}은 <b>저장소 루트</b>에 있다 — commerce와 personalization이
 * 같이 쓰는 자산이라 어느 한쪽 안으로 넣지 않았다. 그래서 그 파일들을 읽으려면 한 단계 위를 봐야 한다.
 *
 * <p><b>왜 {@code "../docs/..."}로 안 적나</b>: 그렇게 적으면 디렉터리를 한 번 더 옮길 때 같은 일이
 * 또 터진다. 깊이를 박아 두는 대신 <b>표식을 찾아 올라간다</b> — 표식은 {@code CONTRIBUTING.md}와
 * {@code docs/}가 같이 있는 디렉터리다.
 */
public final class RepoRoot {

    private static final Path ROOT = find();

    private RepoRoot() {
    }

    /** 저장소 루트 기준 경로. 예: {@code RepoRoot.resolve("docs/09-ERD-설계.md")} */
    public static Path resolve(String relative) {
        return ROOT.resolve(relative);
    }

    public static Path path() {
        return ROOT;
    }

    private static Path find() {
        Path dir = Path.of("").toAbsolutePath();
        for (Path p = dir; p != null; p = p.getParent()) {
            if (Files.isRegularFile(p.resolve("CONTRIBUTING.md")) && Files.isDirectory(p.resolve("docs"))) {
                return p;
            }
        }
        // 못 찾으면 조용히 잘못된 경로를 돌려주는 대신 여기서 멈춘다 — 빈 파일을 읽고
        // "검사할 것이 없어서 통과" 하는 것이 가장 나쁜 실패다.
        throw new IllegalStateException(
                "저장소 루트를 못 찾았다(CONTRIBUTING.md + docs/ 가 있는 디렉터리). 작업 디렉터리: " + dir);
    }
}
