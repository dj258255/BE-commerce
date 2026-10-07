package com.beomsu.becommerce.shorts.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@link ShortsStorage}의 개발용 구현 — MinIO 대신 로컬 디스크를 쓴다.
 *
 * <p>실제 presigned PUT 서명은 하지 않는다(샌드박스에 오브젝트 스토리지가 없다, R21 범위 메모 참고).
 * {@code issueUploadUrl}은 디스크 상의 대상 경로를 그대로 돌려주고, {@code exists}는 그 경로에
 * 파일이 실제로 생겼는지를 본다 — 운영에서 MinIO 구현으로 바뀌어도 "업로드 완료 주장을 그대로
 * 믿지 않고 저장소에 확인한다"는 계약은 같다.
 */
@Component
public class LocalFileShortsStorage implements ShortsStorage {

    private final Path baseDir;

    public LocalFileShortsStorage(
            @Value("${app.shorts.storage.base-dir:${java.io.tmpdir}/becommerce-shorts}") String baseDir) {
        this.baseDir = Path.of(baseDir);
        try {
            Files.createDirectories(this.baseDir);
        } catch (IOException e) {
            throw new UncheckedIOException("숏폼 저장소 디렉터리를 만들 수 없습니다: " + this.baseDir, e);
        }
    }

    @Override
    public String issueUploadUrl(String objectKey, String contentType) {
        return "file://" + resolve(objectKey);
    }

    @Override
    public boolean exists(String objectKey) {
        return Files.exists(resolve(objectKey));
    }

    private Path resolve(String objectKey) {
        return baseDir.resolve(objectKey);
    }
}
