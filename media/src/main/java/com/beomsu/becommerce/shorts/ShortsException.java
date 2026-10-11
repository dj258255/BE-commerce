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

    /**
     * R25: 연결하려는 상품이 카탈로그에 없음. 새 코드를 만들지 않는다 — 카탈로그·위시리스트와
     * 같은 상황이므로 같은 코드({@code PRODUCT_NOT_FOUND})를 쓴다({@code WishlistException} 참고).
     */
    public static ShortsException productNotFound(long productId) {
        return new ShortsException("PRODUCT_NOT_FOUND", "상품을 찾을 수 없습니다: " + productId);
    }

    /**
     * R25 경계: 영상 하나에 연결 가능한 상품 수(기본 10개)를 넘는 새 상품을 연결하려 함.
     * {@code shortVideoId}는 저장 전(id 미발급) 애그리거트에서도 호출될 수 있어 {@code Long}으로
     * 받는다 — {@code long}이면 {@code null} 자동 언박싱이 NPE가 된다.
     */
    public static ShortsException tooManyLinkedProducts(Long shortVideoId, int max) {
        return new ShortsException("TOO_MANY_LINKED_PRODUCTS",
                "영상 하나에 연결할 수 있는 상품은 %d개까지입니다: 영상 %s".formatted(max, shortVideoId));
    }

    /** R27: 시청 신호 기록인데 로그인도 안 했고 익명 식별자도 안 보냄 — 둘 중 하나는 있어야 기록할 수 있다. */
    public static ShortsException viewerIdentityRequired() {
        return new ShortsException("VIEWER_IDENTITY_REQUIRED",
                "비로그인 요청은 익명 식별자(anonymousId)가 있어야 시청 신호를 기록할 수 있습니다");
    }

    /** R27: 시청 신호 값이 유효하지 않음(음수 시청 시간 등). */
    public static ShortsException invalidViewSignal(String message) {
        return new ShortsException("INVALID_VIEW_SIGNAL", message);
    }
}
