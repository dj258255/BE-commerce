package com.beomsu.becommerce.shorts;

import com.beomsu.becommerce.shared.DomainException;

/** 숏폼 도메인 예외(R22). code는 10-API-스펙 문서의 에러 코드 체계에 맞춘다. */
public class ShortsException extends DomainException {

    public ShortsException(String code, String message) {
        super(code, message);
    }

    public static ShortsException invalidTransition(ShortVideoStatus from, ShortVideoStatus to) {
        return new ShortsException("INVALID_STATE_TRANSITION",
                "허용되지 않은 상태 전이입니다: %s → %s".formatted(from, to));
    }

    /** R21: 영상 길이가 1초 미만이거나 {@link UploadMeta#MAX_DURATION_SECONDS}(60초)를 넘음. */
    public static ShortsException invalidDuration(int durationSeconds) {
        return new ShortsException("INVALID_DURATION",
                "영상 길이는 1초 이상 %d초 이하여야 합니다: %d초"
                        .formatted(UploadMeta.MAX_DURATION_SECONDS, durationSeconds));
    }

    /** R21: 파일 크기가 0 이하이거나 {@link UploadMeta#MAX_FILE_SIZE_BYTES}(200MB)를 넘음. */
    public static ShortsException invalidFileSize(long fileSizeBytes) {
        return new ShortsException("INVALID_FILE_SIZE",
                "파일 크기는 %dMB 이하여야 합니다: %d bytes"
                        .formatted(UploadMeta.MAX_FILE_SIZE_BYTES / (1024 * 1024), fileSizeBytes));
    }

    /** R21: 세로(9:16) 비율이 아님. */
    public static ShortsException invalidAspectRatio(int width, int height) {
        return new ShortsException("INVALID_ASPECT_RATIO",
                "세로(9:16) 비율 영상만 올릴 수 있습니다: %dx%d".formatted(width, height));
    }

    /** R21: 업로드 완료 알림이 왔지만 저장소에서 객체를 확인할 수 없음 — 클라이언트 주장을 믿지 않는다. */
    public static ShortsException uploadNotFound(String objectKey) {
        return new ShortsException("UPLOAD_NOT_FOUND",
                "저장소에서 업로드된 파일을 찾을 수 없습니다. 발급받은 URL로 먼저 업로드를 끝내 주세요.");
    }

    public static ShortsException notFound(long id) {
        return new ShortsException("SHORT_VIDEO_NOT_FOUND", "숏폼 영상을 찾을 수 없습니다: " + id);
    }

    /** 업로드한 판매자 본인이 아닌 접근 — IDOR 방지. */
    public static ShortsException forbidden(long id) {
        return new ShortsException("SHORT_VIDEO_FORBIDDEN", "이 숏폼 영상에 대한 권한이 없습니다: " + id);
    }
}
