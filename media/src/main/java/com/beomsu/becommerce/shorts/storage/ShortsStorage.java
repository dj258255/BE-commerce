package com.beomsu.becommerce.shorts.storage;

/**
 * 숏폼 원본 업로드 저장소(R21) — 서버를 거치지 않는 presigned 직접 업로드의 경계.
 *
 * <p>운영 목표는 MinIO다(명세 5절). 샌드박스에 오브젝트 스토리지가 없는 지금은 인터페이스만
 * 고정하고, 개발용 구현({@link LocalFileShortsStorage})으로 로컬 파일을 쓴다. 변환 워커가
 * 붙는 다음 단계에서 실제 바이트를 읽는 쪽(다운로드)도 이 인터페이스에 추가될 수 있다.
 */
public interface ShortsStorage {

    /**
     * 업로드용 URL을 발급한다. 운영 구현은 서명된 PUT URL(짧은 만료)을 돌려줘야 한다 — 서버가
     * 파일 바이트를 중계하지 않는다(명세 "서버를 거치지 않고 저장소로 바로 올라가야 한다").
     */
    String issueUploadUrl(String objectKey, String contentType);

    /** 해당 키의 객체가 저장소에 실제로 존재하는지. 업로드 완료 알림의 클라이언트 주장을 믿지 않는다. */
    boolean exists(String objectKey);
}
