package com.beomsu.becommerce.shorts;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 숏폼 변환 산출물(HLS 재생목록·세그먼트·썸네일)을 로컬 디스크에서 찾아 주는 서비스(R26 재생).
 *
 * <p><b>운영에서는 이 클래스 전체가 없어진다.</b> 로컬 파일 저장소({@code LocalFileShortsStorage})
 * 전제라 존재하는 임시 경계다 — 운영은 오브젝트 스토리지(MinIO/S3)에 올라간 산출물을 CDN이나
 * 스토리지가 직접 서빙해야 한다(presigned GET URL 또는 CDN 오리진 연결). Spring이 바이트를
 * 중계하는 지금 구조는 "서버를 거치지 않는다"는 R21 원칙과도 어긋나는 임시 조치다 — ADR-081의
 * "다시 볼 조건"에 이 경계를 적어 둔다.
 *
 * <p>{@code app.shorts.storage.base-dir}는 {@code LocalFileShortsStorage}가 쓰는 바로 그
 * 프로퍼티다(같은 디스크를 봐야 한다) — 두 클래스가 각자 주입받는 이유는 둘이 서로를 몰라도
 * 되게(저장소 경계, R21) 하기 위해서다.
 */
@Service
public class ShortsMediaService {

    private final ShortVideoRepository repository;
    private final Path baseDir;

    public ShortsMediaService(ShortVideoRepository repository,
            @Value("${app.shorts.storage.base-dir:${java.io.tmpdir}/becommerce-shorts}") String baseDir) {
        this.repository = repository;
        this.baseDir = Path.of(baseDir);
    }

    /**
     * {@code shortVideoId}가 READY이고 {@code relativePath}가 그 영상의 변환 산출물 디렉터리
     * (마스터 재생목록·썸네일과 같은 폴더, 그 하위 폴더 포함 — 세 렌디션은 "1080/out.m3u8"처럼
     * 한 단계 아래에 있다) 안을 가리킬 때만 실제 파일 경로를 돌려준다.
     *
     * <p><b>경로 조작 방어(R26)</b>: {@code relativePath}를 그 디렉터리 밑에 이어붙인 뒤
     * {@code normalize()}로 {@code ..}를 풀고, 그 결과가 여전히 그 디렉터리 "안"인지
     * ({@code startsWith})를 확인한다 — {@code ../../etc/passwd} 같은 입력은 정규화 후 그
     * 디렉터리를 벗어나므로 막힌다. 존재하지 않거나 일반 파일이 아니면(디렉터리 등) 404와
     * 같은 취급(찾을 수 없음)으로 던진다 — 영상이 없는지, READY가 아닌지, 경로가 잘못됐는지를
     * 응답에서 구분해 주지 않는다(내부 구조를 드러내지 않는다).
     */
    @Transactional(readOnly = true)
    public Path resolve(long shortVideoId, String relativePath) {
        ShortVideo video = repository.findById(shortVideoId)
                .filter(v -> v.getStatus() == ShortVideoStatus.READY)
                .orElseThrow(() -> ShortsException.notFound(shortVideoId));

        Path mediaRoot = baseDir.resolve(Path.of(video.getMasterPlaylistPath()).getParent()).normalize();
        Path requested = mediaRoot.resolve(relativePath).normalize();
        if (!requested.startsWith(mediaRoot) || !Files.isRegularFile(requested)) {
            throw ShortsException.notFound(shortVideoId);
        }
        return requested;
    }
}
