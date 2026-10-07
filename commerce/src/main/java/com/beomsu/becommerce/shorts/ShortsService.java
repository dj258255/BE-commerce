package com.beomsu.becommerce.shorts;

import com.beomsu.becommerce.shared.Ulid;
import com.beomsu.becommerce.shorts.storage.ShortsStorage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 숏폼 애플리케이션 서비스 — shorts 모듈의 공개 진입점(R21).
 *
 * <p>업로드 시작({@link #startUpload})에서 {@link UploadMeta}로 길이·크기·세로 비율을 검증하고,
 * 저장소에 presigned URL을 발급받아 {@link ShortVideo}를 UPLOADING으로 만든다. 업로드 완료 알림
 * ({@link #completeUpload})은 클라이언트 주장을 그대로 믿지 않고 {@link ShortsStorage#exists}로
 * 객체가 실제로 올라왔는지 확인한 뒤에만 UPLOADED로 전이한다.
 *
 * <p>소유권은 전부 {@code sellerId}(principal에서 얻은 값)로 검증한다(IDOR 방지) — 남의 영상
 * id를 넣어도 {@link ShortsException#forbidden}으로 막힌다.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class ShortsService {

    private final ShortVideoRepository repository;
    private final ShortsStorage storage;

    /** 업로드 시작 — 메타를 검증하고 객체 키를 발급해 UPLOADING 레코드를 만든다. */
    public StartUploadResult startUpload(long sellerId, UploadMeta meta) {
        String objectKey = "shorts/%d/%s".formatted(sellerId, Ulid.generate());
        ShortVideo video = ShortVideo.upload(sellerId, objectKey, meta);
        repository.save(video);
        String uploadUrl = storage.issueUploadUrl(objectKey, meta.contentType());
        return new StartUploadResult(video.getId(), uploadUrl);
    }

    /** 업로드 완료 알림 — 저장소에 객체가 실제로 있을 때만 UPLOADED로 전이한다. */
    public ShortVideoView completeUpload(long sellerId, long id) {
        ShortVideo video = findOwned(sellerId, id);
        if (!storage.exists(video.getObjectKey())) {
            throw ShortsException.uploadNotFound(video.getObjectKey());
        }
        video.markUploaded();
        return ShortVideoView.from(video);
    }

    @Transactional(readOnly = true)
    public ShortVideoView get(long sellerId, long id) {
        return ShortVideoView.from(findOwned(sellerId, id));
    }

    private ShortVideo findOwned(long sellerId, long id) {
        ShortVideo video = repository.findById(id).orElseThrow(() -> ShortsException.notFound(id));
        if (video.getSellerId() != sellerId) {
            throw ShortsException.forbidden(id);
        }
        return video;
    }

    /** 업로드 시작 응답 — 발급된 숏폼 id와 업로드 대상 URL. */
    public record StartUploadResult(Long shortVideoId, String uploadUrl) {
    }
}
