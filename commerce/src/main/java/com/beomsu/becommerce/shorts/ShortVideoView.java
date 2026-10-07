package com.beomsu.becommerce.shorts;

import java.time.Instant;

/** 숏폼 영상 조회 뷰 — 업로드 완료 응답과 단건 조회 응답을 함께 쓴다. */
public record ShortVideoView(
        Long id,
        ShortVideoStatus status,
        Integer durationSeconds,
        Long fileSizeBytes,
        Integer width,
        Integer height,
        String failureReason,
        int retryCount,
        Instant createdAt,
        Instant updatedAt) {

    public static ShortVideoView from(ShortVideo v) {
        return new ShortVideoView(v.getId(), v.getStatus(), v.getDurationSeconds(), v.getFileSizeBytes(),
                v.getWidth(), v.getHeight(), v.getFailureReason(), v.getRetryCount(),
                v.getCreatedAt(), v.getUpdatedAt());
    }
}
