package com.beomsu.becommerce.shorts;

/**
 * 변환 산출물(R23) — 세 화질 렌디션 경로, 마스터 재생목록, 썸네일.
 *
 * <p>{@link #missingField()}가 null이 아니면 READY로 갈 자격이 없다(R23.2) — 다섯 항목 중
 * 하나라도 비어 있으면 {@link ShortVideo#completeTranscoding}이 변환 실패(FAILED)로 취급한다.
 */
public record TranscodeOutput(
        String rendition1080pPath,
        String rendition720pPath,
        String rendition480pPath,
        String masterPlaylistPath,
        String thumbnailPath) {

    /** 비어 있는 첫 항목의 한글 이름. 다섯 항목이 모두 있으면 null. */
    public String missingField() {
        if (isBlank(rendition1080pPath)) {
            return "1080p 렌디션";
        }
        if (isBlank(rendition720pPath)) {
            return "720p 렌디션";
        }
        if (isBlank(rendition480pPath)) {
            return "480p 렌디션";
        }
        if (isBlank(masterPlaylistPath)) {
            return "마스터 재생목록";
        }
        if (isBlank(thumbnailPath)) {
            return "썸네일";
        }
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
