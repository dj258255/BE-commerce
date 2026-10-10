/**
 * 숏폼(shorts) 모듈 — 세로 짧은 영상의 업로드~변환 파이프라인·상품 연결·피드(R21·R22·R25·R26).
 *
 * <p>이 모듈은 R32에 따라 저장소 최상위 {@code media/} 영역(별도 Gradle 모듈)에 산다. commerce
 * 애플리케이션과 같은 jar로 배포하며(별도 서비스로 나누지 않는다, ADR-080), commerce가 이
 * 모듈에 의존한다({@code implementation project(':media')}) — 반대 방향 의존은 없다.
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
 * 이름·가격은 {@link com.beomsu.becommerce.shorts.ProductLookup}(media가 정의한 포트)로만
 * 얻는다. 구현은 commerce 쪽({@code ShortsProductLookupAdapter}, order의
 * {@code ProductCatalogFacts}를 감싼다)에 있지만 media 코드는 그 존재를 모른다 — Gradle
 * 순환 의존을 피하려고 의존을 역전했다(ADR-080). Modulith 관점에서는 이전과 같은 모듈
 * 경계이므로 {@code allowedDependencies}는 그대로 {@code shared}·{@code order}다(어댑터가
 * 물리적으로 commerce에 있어도 패키지 이름은 같다).
 *
 * <p>{@link com.beomsu.becommerce.shorts.web.ShortsFeedController}가 READY 숏폼만 id
 * 내림차순(최신순)으로 커서 페이지네이션해 내준다(R26) — 비로그인도 호출할 수 있게
 * {@code SecurityConfig}가 이 경로만 예외로 연다. 세로 스와이프·프리페치 화면은 apps/web에 있다.
 *
 * <p>업로드 완료(UPLOADED)는 {@link com.beomsu.becommerce.shorts.ShortUploadedEvent}로 Outbox에
 * 적재되고(R23, ADR-002와 같은 구조), {@link com.beomsu.becommerce.shorts.ShortsTranscodeListener}가
 * 커밋 후 받아 {@link com.beomsu.becommerce.shorts.ShortsTranscodeService}(오케스트레이터,
 * {@code @Transactional}이 아니다)로 PROBING → TRANSCODING → READY(실패는 FAILED, 3회 재시도
 * 소진 시 QUARANTINED)를 끌고 간다. 상태 전이 하나하나는
 * {@link com.beomsu.becommerce.shorts.ShortVideoTransitionService}의 짧은
 * {@code @Transactional} 메서드가 커밋하고, FFmpeg 호출(probe·transcode, 수 초~수십 초)은
 * 그 트랜잭션 밖에서 돈다 — 변환 중 DB 커넥션을 붙잡지 않는다. UPLOADED/FAILED→PROBING·
 * PROBING→TRANSCODING 전이는 조건부 UPDATE
 * ({@link com.beomsu.becommerce.shorts.ShortVideoRepository#claimTransition})라 두 워커가
 * 동시에 같은 영상을 집어도 하나만 성공한다(지금은 워커가 하나지만 이 보장은 코드로 남아 있다).
 * 실제 FFmpeg 호출은 {@link com.beomsu.becommerce.shorts.TranscodeRunner} 포트 뒤에 있고, 운영
 * 빈은 {@code FfmpegTranscodeRunner}(ProcessBuilder로 실제 ffmpeg를 부른다, R23 3단계)뿐이다 —
 * 결정적인 가짜({@code FakeTranscodeRunner})는 테스트 소스에만 있고 빈으로 등록되지 않는다.
 * 명세 5절의 MinIO·Kafka·FFmpeg 워커 출발점과 다른 선택(같은 jar의 worker 프로파일, Outbox,
 * 로컬 저장소)의 근거는 ADR-081에 있다. 변환 리스너는 {@code app.shorts.transcode.enabled=true}
 * (worker 프로파일)에서만 빈으로 등록되고 API 배포에는 없다.
 *
 * <p>변환 결과(세 화질 렌디션·마스터 재생목록·썸네일)는
 * {@link com.beomsu.becommerce.shorts.ShortVideo#completeTranscoding}이 다섯 항목이 모두 있을
 * 때만 READY로 기록한다(R23.2) — 하나라도 없으면 FAILED와 이유를 남긴다. 주문·결제·재고 확정
 * 로직은 이 모듈에 두지 않는다 — 필요하면
 * {@link com.beomsu.becommerce.shorts.ProductLookup} 같은 포트를 추가로 정의한다.
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = { "shared", "order" }
)
package com.beomsu.becommerce.shorts;
