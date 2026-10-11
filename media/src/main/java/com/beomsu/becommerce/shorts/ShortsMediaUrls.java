package com.beomsu.becommerce.shorts;

/**
 * 숏폼 변환 산출물(HLS 재생목록·세그먼트·썸네일)을 가리키는 공개 URL을 만든다(R26 재생).
 *
 * <p>{@code ShortsMediaController}(web 패키지)의 {@code GET /api/v1/shorts/{id}/media/**}와
 * 짝이다 — 두 파일을 같이 고쳐야 한다. 경로를 상수 하나로 묶지 않고 문자열로 둔 이유는
 * 컨트롤러가 다른 Gradle 소스셋(web 서브패키지)에 있어 상수를 공유하면 패키지 경계가 흐려지기
 * 때문이다 — 대신 양쪽 자바독에 서로를 링크해 둔다.
 *
 * <p>여기서 돌려주는 주소는 <b>이 애플리케이션 자신의 상대 경로</b>다(절대 호스트를 모른다) —
 * 브라우저가 같은 origin으로 다시 불러온다({@code apps/web}의 {@code next.config.ts} rewrite가
 * 그 경로를 Spring으로 보낸다, 피드 조회 경로와 같은 방식).
 */
final class ShortsMediaUrls {

    private ShortsMediaUrls() {
    }

    /**
     * {@code storedRelativePath}(예: {@code "shorts/3/01ABC.out/master.m3u8"})의 파일명만 뽑아
     * {@code /api/v1/shorts/{id}/media/{파일명}}을 만든다 — 그 파일명이 변환 산출물 디렉터리
     * (= master 재생목록과 썸네일이 같이 있는 바로 그 폴더) 바로 아래에 있다는 전제다
     * ({@code ShortsMediaService}가 그 전제로 경로를 푼다).
     */
    static String of(long shortVideoId, String storedRelativePath) {
        int lastSlash = storedRelativePath.lastIndexOf('/');
        String fileName = lastSlash < 0 ? storedRelativePath : storedRelativePath.substring(lastSlash + 1);
        return "/api/v1/shorts/%d/media/%s".formatted(shortVideoId, fileName);
    }
}
