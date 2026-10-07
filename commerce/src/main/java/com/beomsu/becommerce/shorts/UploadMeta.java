package com.beomsu.becommerce.shorts;

/**
 * 업로드 시작 요청의 영상 메타(R21) — 길이·크기·세로 비율 제약을 생성 시점에 검증하는 값 객체.
 *
 * <p>클라이언트가 보낸 메타를 그대로 믿지 않고(실제 파일 검증은 변환 단계의 PROBING이 한다) 여기서는
 * presigned URL을 내주기 전에 명백히 규격 밖인 요청을 빠르게 거절한다 — 60초 넘는 영상이나 200MB
 * 넘는 파일에 업로드 URL을 내주는 낭비를 막는다.
 */
public record UploadMeta(int durationSeconds, long fileSizeBytes, int width, int height, String contentType) {

    public static final int MAX_DURATION_SECONDS = 60;
    public static final long MAX_FILE_SIZE_BYTES = 200L * 1024 * 1024;

    public UploadMeta {
        if (durationSeconds <= 0 || durationSeconds > MAX_DURATION_SECONDS) {
            throw ShortsException.invalidDuration(durationSeconds);
        }
        if (fileSizeBytes <= 0 || fileSizeBytes > MAX_FILE_SIZE_BYTES) {
            throw ShortsException.invalidFileSize(fileSizeBytes);
        }
        // 세로(9:16) 비율만 허용 — width:height = 9:16 ⇔ width*16 = height*9 (정수 비교, 오차 없음).
        if (width <= 0 || height <= 0 || width * 16L != height * 9L) {
            throw ShortsException.invalidAspectRatio(width, height);
        }
    }
}
