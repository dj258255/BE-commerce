/**
 * 숏폼(shorts) 모듈 — 세로 짧은 영상의 업로드~변환 파이프라인과 상품 연결(R21·R22·R25).
 *
 * <p>{@link com.beomsu.becommerce.shorts.ShortVideo}가 상태 전이
 * (UPLOADING → UPLOADED → PROBING → TRANSCODING → READY/FAILED)와 재시도(3회)·격리를 갖는다(R22).
 * {@link com.beomsu.becommerce.shorts.web.ShortsController}가 업로드 시작·완료 알림·조회 API를
 * 판매자(ROLE_SELLER)에게 내주고(R21), 업로드 시작 시 {@link com.beomsu.becommerce.shorts.UploadMeta}로
 * 길이(60초)·크기(200MB)·세로 비율(9:16)을 검증한다. 저장소는
 * {@link com.beomsu.becommerce.shorts.storage.ShortsStorage} 인터페이스 뒤로 숨기고, 샌드박스에
 * MinIO가 없는 지금은 로컬 파일 구현을 쓴다.
 *
 * <p>영상 하나에 상품을 여러 개 연결·해제할 수 있다(R25) — 상품 실존 확인과 조회 응답의
 * 이름·가격은 order가 공개한 읽기 포트({@link com.beomsu.becommerce.order.ProductCatalogFacts},
 * ADR-018, wishlist와 같은 경로)로만 얻는다. order는 shorts를 모르므로 순환은 없다.
 *
 * <p>변환 워커·피드·media/ 분리는 다음 단계다. 결제·재고 확정은 호출하지 않는다
 * (allowedDependencies는 shared·order뿐).
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = { "shared", "order" }
)
package com.beomsu.becommerce.shorts;
