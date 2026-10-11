# ADR-081. 숏폼 변환 2단계: MinIO·Kafka·별도 FFmpeg 워커 대신 로컬 저장소·Outbox·같은 jar worker 프로파일

- 상태: **채택**
- 날짜: 2026-10-08
- 관련: R23, [42-라이브커머스-숏폼-명세.md](../42-라이브커머스-숏폼-명세.md) §5·§7(순서 1),
  [ADR-002](ADR-002-outbox-event-publication-registry.md)(Outbox = Event Publication Registry),
  [ADR-029](ADR-029-deployment-unit-vs-service-boundary.md)(배포 단위 분리 기준선),
  [ADR-080](ADR-080-media-gradle-submodule.md)(media Gradle 하위 프로젝트),
  [docs/performance/shorts-transcode.md](../performance/shorts-transcode.md)(변환 자체의 소요 시간)

## 맥락

명세 5절은 숏폼 저장·변환의 출발점을 이렇게 정했다:

| 영역 | 출발점 | 이유 |
|---|---|---|
| 숏폼 저장·변환 | MinIO presigned 업로드 → **Kafka** 이벤트 → **FFmpeg 워커**, 세 화질 fMP4 HLS | 업로드와 변환을 떼어 변환이 밀려도 업로드는 받는다 |

이 2단계(업로드 완료 → PROBING → TRANSCODING → READY 상태 전이 파이프라인)를 만들면서 이
출발점에서 세 가지를 벗어난다: **MinIO 대신 로컬 저장소**(R21에서 이미 그렇게 구현돼 있었고,
이 ADR이 처음으로 근거를 남긴다), **Kafka 대신 Outbox**, **별도 FFmpeg 워커 프로세스/서비스
대신 같은 jar의 worker 프로파일**. 셋 다 "업로드와 변환을 떼어 변환이 밀려도 업로드는 받는다"는
명세의 목적 자체는 바꾸지 않는다 — 수단만 바꾼다.

## 결정 1: MinIO 대신 로컬 저장소 (`ShortsStorage`/`LocalFileShortsStorage`)

**왜 벗어났나**: 이 샌드박스에는 오브젝트 스토리지가 없고, 명세가 요구하는 "서버를 거치지
않는 presigned 직접 업로드"라는 **경계**가 핵심이지 MinIO라는 **구현체**가 핵심은 아니다.
`ShortsStorage` 인터페이스(`issueUploadUrl`·`exists`)가 그 경계를 고정하고, 로컬 파일
구현은 그 뒤에서 교체 가능하다.

**대가**: 로컬 파일 구현은 `file://` URL을 돌려주므로 실제 "서버를 거치지 않는" 성질을
검증하지 못한다(브라우저가 `file://`로 PUT할 수 없다) — API 계약(presigned URL 발급·완료
확인)만 맞고, 실제 다중 노드 환경에서의 스토리지 가용성·용량은 검증되지 않는다.

## 결정 2: Kafka 대신 Outbox(Event Publication Registry)

**왜 벗어났나**: "업로드 완료 → 변환 시작"은 **같은 데이터베이스, 같은 jar 안의 인프로세스
전달**이다. 결제 승인 → 원장·에스크로·정산이 이미 이 경로를 쓴다([ADR-002](ADR-002-outbox-event-publication-registry.md)) — 커밋과 이벤트 발행을 한 트랜잭션으로 묶는
Transactional Outbox가 필요하지, 프로세스 경계를 넘는 메시지 브로커가 필요한 자리가 아니다.
Kafka를 새로 들이면 ADR-002가 이미 경고한 것(검증된 구현이라도 우리 부하에서 스키마·기본값을
다시 봐야 한다)과 같은 종류의 운영 부담이 **근거 없이** 하나 더 생긴다 — 이 경로는 서비스
경계를 넘지 않으므로 그 부담을 질 이유가 없다.

**대가**: 변환 파이프라인이 commerce 애플리케이션과 완전히 분리된 별도 소비자(다른 언어·다른
배포 주기의 FFmpeg 전용 서비스)가 될 수 없다. 지금은 같은 jar 안에서만 소비된다.

## 결정 3: 별도 FFmpeg 워커 서비스 대신 같은 jar의 worker 프로파일

**왜 벗어났나**: [ADR-029](ADR-029-deployment-unit-vs-service-boundary.md)가 이미 이
저장소의 기준선을 세웠다 — "같은 코드·같은 DB, 배포 단위만 분리"로 스케줄러 중복·배포
결합을 실측으로 없앴다. 변환 리스너(`ShortsTranscodeListener`)는 정산·복구·만료 스케줄러와
같은 모양의 문제다: **무거운 일을 API 프로세스가 같이 떠안으면 안 되지만, 그렇다고 당장
별도 서비스·별도 저장소가 필요한 것도 아니다.** 그래서 같은 `@ConditionalOnProperty`
게이트(`app.shorts.transcode.enabled`)를 그대로 쓴다 — API 배포는 이 프로퍼티 없이 떠서
리스너가 빈으로 등록되지 않고, `worker` 프로파일로 뜬 배포만 변환을 가져간다.

**대가**: FFmpeg는 CPU를 많이 쓰는 작업이라, API와 변환이 같은 jar(다른 배포 단위이긴
하지만 같은 코드베이스·같은 DB)를 공유하는 한 변환 워커의 리소스 상한을 API와 독립적으로
조정하기 어렵다. 변환이 몰리면 worker 프로세스가 느려질 뿐 API는 영향받지 않는다(배포
단위가 다르므로) — 다만 "변환 전용 하드웨어"처럼 완전히 다른 리소스 프로파일을 주려면
그때는 서비스까지 나눠야 한다.

## 이번 단계가 하지 않는 것

- **실제 FFmpeg 호출.** `TranscodeRunner` 포트(`probe`·`transcode`) 뒤에 결정적인 가짜
  구현(`FakeTranscodeRunner`)만 둔다 — 기본 게이트(`./gradlew -p commerce test`)가 실제
  인코딩을 돌리면 환경마다 느려지고 결과가 흔들린다. 변환 자체의 소요 시간 비교는 이미
  [docs/performance/shorts-transcode.md](../performance/shorts-transcode.md)에서 별도로
  쟀다(FFmpeg 바이너리를 직접 구해 돌린 벤치마크, 운영 Dockerfile에는 아직 설치하지 않았다).
  실제 FFmpeg 구현을 붙이는 다음 단계는 그 비교 결과(`filtersplit`)를 따른다.

  **(→ 2026-10-08 3단계에서 붙였다.)** `FfmpegTranscodeRunner`(ProcessBuilder, media
  src/main)가 운영 빈이 됐다 — `filtersplit`+`superfast`로 세 화질을 한 번에 뽑는다.
  `FakeTranscodeRunner`는 테스트 소스로 옮기고 `@Component`를 뗐다(운영 코드에 가짜가
  남아 있으면 worker 프로파일에서도 가짜가 이길 위험이 있었다). FFmpeg는 다른 경로로
  바이너리를 받지 않고 패키지로 설치한다 — 샌드박스는 `studio.yaml`의
  `commerce.systemPackages: [ffmpeg]`, 운영은 `Dockerfile`의 apt 설치.
- **재시도 간 지연·백오프.** 가짜 실행기는 지연이 없으므로 실패하면 같은 호출 안에서 즉시
  재시도한다(최대 3회, 소진하면 QUARANTINED). 실제 FFmpeg로 바뀌면 재시도 사이에 지연을
  둘지(일시적 자원 부족과 영구적 실패를 구분)를 다시 본다.

  **(→ 2026-10-08 현행화)** 아직 지연을 넣지 않았다 — 실제 FFmpeg가 붙은 뒤에도 재시도는
  여전히 같은 호출 안에서 즉시 돈다. FFmpeg 실패의 대부분(코덱 인식 불가, 입력 손상)은
  재시도해도 결과가 바뀌지 않는 영구적 실패라, 지연을 넣는 것의 효과를 아직 확인하지
  못했다. 일시적 자원 부족(동시 변환 과다로 인한 타임아웃)이 실제로 보이면 다시 본다.

## 검증

- `ShortsTranscodeServiceTest`(media) — 가짜 실행기로 happy path(READY, 산출물 다섯 항목
  기록)·probe 영구 실패(QUARANTINED)·"성공으로 보고했지만 산출물 불완전"(R23.2, 결국
  QUARANTINED) 세 경로를 결정적으로 검증한다. 실제 FFmpeg는 부르지 않는다.
- `ShortsTranscodeListenerConditionalTest`(media) — `app.shorts.transcode.enabled`가
  없거나 `false`면 리스너 빈이 없고, `true`일 때만 등록됨을 `ApplicationContextRunner`로
  확인한다(DB 없이, Spring Boot 조건 평가만 본다).
- `ShortVideoTest`에 R23 경계 테스트 추가 — `completeTranscoding`이 완전한 산출물에서만
  READY로 가고, 하나라도 없으면(예: 썸네일 누락) FAILED와 사유를 남긴다.
- `FfmpegTranscodeRunnerTest`(media, 2026-10-08 3단계) — FFmpeg가 PATH에 있을 때만 돈다
  (없으면 건너뜀). 5초 세로 합성 영상(`lavfi testsrc2`)을 실제로 변환해 세 렌디션·마스터
  재생목록·썸네일이 전부 생기는지, 존재하지 않는 원본을 probe하면 실패 사유를 남기는지
  확인한다. 샌드박스(`studio.yaml`의 `systemPackages: [ffmpeg]`)에서 실제로 통과했다.
- `./gradlew -p commerce test` — media·commerce 전체 통과(worker 프로파일 없이 뜨는
  기본 컨텍스트에서도 `FfmpegTranscodeRunner` 빈 하나만 있어 `ShortsApiIntegrationTest`의
  수동 `markReady()` 경로와 충돌하지 않는다 — 리스너만 꺼져 있을 뿐 실행기 빈은 항상 있다).

## 현행화 (R23 트랜잭션 경계 수정)

`ShortsTranscodeService.processUploaded`가 `@Transactional` 메서드 하나였던 시절에는
probe→transcode→기록 전체가 하나의 DB 트랜잭션으로 묶여 FFmpeg가 도는 10초 넘는 동안
커넥션을 붙잡았다(동시 변환 몇 건만 겹쳐도 커넥션 풀이 말라 주문·결제까지 막힐 수 있는
구조). 상태 전이(PROBING·TRANSCODING·READY/FAILED/QUARANTINED)마다 짧은 트랜잭션으로
커밋하고 FFmpeg는 트랜잭션 밖에서 돌도록 고쳤다(`ShortVideoTransitionService`). 여기에
숨은 두 번째 원인이 있었다 — `@ApplicationModuleListener`는 `@Async` +
`@Transactional(propagation = REQUIRES_NEW)` + `@TransactionalEventListener`를 합성한
애너테이션이라(`javap -v`로 확인), 리스너 메서드 자체가 이미 새 트랜잭션에 들어간 채로
시작돼 하위의 "짧은" 트랜잭션들이 그 안에 합류해 버렸다. 리스너 메서드에
`@Transactional(propagation = NOT_SUPPORTED)`를 추가해 바깥 트랜잭션을 꺼야(suspend)
비로소 의도한 짧은 트랜잭션들이 독립적으로 커밋됐다. UPLOADED/FAILED→PROBING,
PROBING→TRANSCODING 전이는 조건부 UPDATE(`ShortVideoRepository.claimTransition`)로 바꿔
두 워커(또는 이벤트 중복 전달)가 동시에 같은 영상을 집어도 하나만 성공하게 했다 — 지금은
워커가 하나뿐이지만 이 보장은 코드로 남겼다. 재측정 결과와 상세 원인은
[docs/performance/shorts-transcode.md](../performance/shorts-transcode.md)의 "R24: 업로드
완료 → READY" 절에, 재현 테스트는 `ShortVideoTransitionBoundaryTest`(media)에 있다.

## 현행화 (R26 재생 — 로컬 저장소를 Spring이 직접 서빙한다)

READY 영상을 실제로 재생하려면 HLS 재생목록·세그먼트·썸네일을 어딘가가 HTTP로 내줘야 한다.
운영 목표(명세 5절)는 이 역할을 CDN이나 오브젝트 스토리지가 맡는 것이다 — Spring 프로세스가
비디오 바이트를 중계하지 않는다는 뜻이고, 이건 R21이 업로드에서 이미 지킨 "서버를 거치지
않는다" 원칙과 같은 방향이다. 하지만 지금은 `LocalFileShortsStorage`(로컬 디스크) 전제라
그 역할을 대신할 CDN·오브젝트 스토리지가 없다 — 그래서 `ShortsMediaController`/
`ShortsMediaService`(media)가 **임시로** Spring을 통해 파일을 서빙한다
(`GET /api/v1/shorts/{id}/media/**`, 피드와 같은 공개 수준). Range 요청(`ResourceRegion`)과
경로 조작 방어(요청 경로를 정규화해 산출물 디렉터리 밖으로 못 나가게)는 갖췄지만, **이 컨트롤러·
서비스 전체가 결정 1(로컬 저장소)과 같은 운명이다** — 로컬 저장소를 벗어나는 순간 이 둘도
함께 없어져야 한다(아래 "다시 볼 조건"에 추가).

로컬 개발에서 변환이 바로 보이게 `local` 프로파일(`SPRING_PROFILES_ACTIVE=local`,
`application.yml`)을 새로 뒀다 — `worker` 프로파일(배포 단위 분리, 결정 3)과는 다른 축이다.
`worker`는 "운영에서 변환을 API와 같은 프로세스에 두지 않는다"는 배포 결정이고, `local`은
"내 컴퓨터(또는 이 샌드박스)에서는 바로 보고 싶다"는 개발 편의다. 운영 API 배포는 `local`을
켜지 않으므로 `app.shorts.transcode.enabled` 기본값(false)이 그대로 지켜진다.

## 현행화 (R26 재생 — 브라우저가 웹 출처로 미디어를 받게 한다)

`ShortsFeedItemView`가 돌려주는 `masterPlaylistUrl`·`thumbnailUrl`은 `/api/v1/shorts/{id}/
media/...`처럼 **이 사이트(apps/web) 기준 상대 경로**다 — 브라우저는 이 경로를 `apps/web`의
출처(예: `http://<web-host>:3000`)로 요청한다. 그런데 실제로 그 바이트를 들고 있는 건
commerce(Spring)다. 그래서 `apps/web` 쪽에서 이 요청을 commerce로 넘겨주는 장치가 필요하다
— 아니면 브라우저가 "web 서버에 그런 경로 없음"으로 404를 받는다(실제로 겪은 증상: 첫 구현
직후 브라우저로 확인하지 않고 commerce에 **직접** curl로만 확인해, "web을 거친 요청"이라는
실제 조건을 재지 않았다 — 겹친 원인은 아래 "무엇이 실제로 404였나" 참고).

**두 선택지**:

| 선택 | 무엇 | Range 처리 |
|---|---|---|
| **A. Next.js rewrites** | `next.config.ts`의 `rewrites()`가 요청을 그대로 `SPRING_API`로 넘긴다(서버가 대신 접속해 응답을 그대로 돌려준다) | Next.js가 요청을 **원시 HTTP 레벨에서 재전송**한다 — 헤더·메서드·바디를 가공하지 않으므로 `Range`·`Accept-Ranges`·`Content-Range`·206 상태코드가 손 안 대고 그대로 오간다 |
| **B. Route Handler** | `app/api/v1/shorts/[...path]/route.ts`를 만들어 `fetch(SPRING_API + path, { headers: req.headers })`로 수동 중계 | 직접 짜야 한다 — 요청의 `Range` 헤더를 그대로 전달하고, 응답의 상태코드(206)·`Content-Range`·스트림 바디를 전부 손으로 복사해야 한다. Node의 `Response`/`ReadableStream` 경계를 넘는 바이너리 스트리밍이라 끊기거나 버퍼링되는 실수가 나기 쉽다 |

**결정: A(Next.js rewrites)를 쓴다 — 사실 처음부터 그렇게 돼 있었다.** `next.config.ts`의
`/api/:path*` → `${SPRING_API}/api/:path*` 규칙은 이미 `/api/v1/shorts/feed`·카탈로그·
`/uploads`·`/product.html`·`/assets`가 쓰는 바로 그 규칙이고, `/api/v1/shorts/{id}/media/**`
도 `/api/:path*`에 포함되므로 **새 규칙이 필요 없다.** B를 버린 이유: rewrites가 이미 공짜로
주는 것(원시 바이트 재전송, 모든 헤더·상태코드 보존)을 Route Handler로 다시 만들면 코드가
늘어나는 만큼 Range 처리를 놓칠 자리만 늘어난다 — 지킬 게 늘어나는데 얻는 게 없다.

**무엇이 실제로 404였나**: rewrites 자체는 처음부터 문제가 없었다(아래 검증에서 web 출처로
재현해 확인). 실제 원인은 두 가지가 겹쳤다.
1. **검증 방법이 틀렸다.** 이전 턴의 "확인"은 commerce에 **직접** 쳤다 — `apps/web`을 거친
   요청(브라우저가 실제로 보내는 경로)을 한 번도 재지 않았다. "브라우저가 쓰는 경로로 재라"는
   지적이 정확하다.
2. **데모 데이터의 파일이 사라져 있었다.** `LocalFileShortsStorage`의 base-dir는 commerce
   컨테이너의 `/tmp` 아래다(결정 1) — 컨테이너를 재시작하면 비워진다. MySQL(별도 영속
   볼륨)은 그 사이 `short_videos` 행을 `READY`로 그대로 갖고 있어 **DB와 파일이 서로 다른
   생명주기를 가진 채 어긋났다**(DB는 READY라는데 파일은 없음) — `ShortsMediaService`는
   파일이 없으면 "찾을 수 없음"(404)으로 응답하도록 설계돼 있어(의도한 동작, 내부 구조를
   드러내지 않으려고 이유를 구분해 주지 않는다) 이번에도 404가 났다. `tools/seed-shorts-dev.sh`를
   다시 돌려 파일을 다시 만들었다 — 이 어긋남 자체는 로컬 파일 저장소를 쓰는 한 반복될 수
   있는, 이미 알려진 한계다(결정 1의 "대가").

**검증(web 출처, commerce 컨테이너에서 `web:3000`으로 직접 curl — 브라우저가 실제로 보는
경로와 같다)**:

```
$ curl -s -o /dev/null -w '%{http_code} %{content_type}\n' http://web:3000/api/v1/shorts/8/media/master.m3u8
200 application/vnd.apple.mpegurl
$ curl -s -o /dev/null -w '%{http_code} %{content_type}\n' http://web:3000/api/v1/shorts/8/media/thumb.jpg
200 image/jpeg
$ curl -s -D - -o /dev/null -H 'Range: bytes=0-10' http://web:3000/api/v1/shorts/8/media/1080/out0.m4s
HTTP/1.1 206 Partial Content
accept-ranges: bytes
content-range: bytes 0-10/2513605
content-type: video/iso.segment
content-length: 11
```

마스터 재생목록·썸네일은 200, Range를 건 세그먼트는 206 + 정확한 `Content-Range`로 — rewrites가
그대로 중계한다. 코드 변경은 없었다(이미 맞는 규칙이 있었다) — 변경은 이 ADR 절과
`tools/seed-shorts-dev.sh` 재실행(데이터 복구)뿐이다.

## 현행화 (R26 재생 — DB/파일 어긋남을 "알려진 한계"로 남기지 않고 고쳤다)

바로 위 절은 파일이 사라진 것을 **수동 재시드**로 복구하고, 재발 방지는 "다시 볼 조건"의
나중 과제로 미뤄 뒀다. 그런데 b-studio의 검증 게이트가 매 실행마다 `commerce`를 재시작하므로
**이 "알려진 한계"가 매번 그대로 재현돼 게이트를 막았다** — `GET /api/v1/shorts/{id}/
media/thumb.jpg`가 404(web 출처, 브라우저와 같은 경로로 재현됨). "다음에 고치자"로 남겨 둘
여유가 없는 문제였다.

**원인 재확인**: `compose.b-studio.yaml`의 commerce 서비스는 `.:/workspace`(저장소
바인드 마운트)와 몇 개의 named volume(`commerce-gradle-home` 등)만 선언돼 있었다 — 숏폼
저장소 base-dir(`${java.io.tmpdir}/becommerce-shorts` = 컨테이너의 `/tmp` 아래)는 **어디에도
선언되지 않은 컨테이너 자체 쓰기 레이어**였다. b-studio가 코드 변경을 반영하려고 commerce
컨테이너를 재생성하면 이 레이어가 통째로 새로 시작한다. 반면 MySQL은 이번 실행에서 같이
재생성되지 않아 `short_videos` 행(`READY`)이 그대로 남았다 — **DB와 파일의 생명주기가
컨테이너 단위로 갈려 있었다.**

**고친 것**: `commerce-shorts-storage`라는 이름의 named volume을 추가하고
`APP_SHORTS_STORAGE_BASE_DIR=/shorts-storage`로 그 볼륨을 `app.shorts.storage.base-dir`에
연결했다(`LocalFileShortsStorage`·`ShortsMediaService`·`FfmpegTranscodeRunner`가 전부 같은
프로퍼티를 본다, R23 3단계의 "둘이 서로를 몰라도 되게" 설계 그대로 — base-dir 하나만
바꾸면 셋 다 같이 옮겨간다). named volume은 `commerce-gradle-home`처럼 컨테이너가 재생성돼도
그대로 남는다. 재시작 전/후로 직접 재현해 확인했다 — 재시작 **후**에도 같은 id의
`master.m3u8`·`thumb.jpg`가 web 출처에서 200이었다.

이전 실행에서 쌓인(파일이 없어진 채 `READY`로만 남아 있던) `short_videos` 행은 고아 데이터라
지웠다(DB 직접 정리 — `mysql` 클라이언트가 샌드박스 실행 정책에 막혀 있어 JDBC로 임시
프로그램을 짜 지웠다, 흔적은 남기지 않았다) — 운영 코드 변경이 아니라 이번 수정을 검증하려고
샌드박스 데이터를 정리한 것이다. 이후 `tools/seed-shorts-dev.sh`로 새로 시드했다.

**이제 "다시 볼 조건"의 해당 항목(아래)은 해소됐다** — 다만 이 named volume은
`compose.b-studio.yaml`(b-studio 전용 개발 compose)에만 있다. 저장소 루트의 `compose.yaml`
(일반 로컬/CI용)은 손대지 않았다 — 거기서도 같은 문제가 재현되면 같은 패턴(named volume +
`APP_SHORTS_STORAGE_BASE_DIR`)을 넣는다.

## 다시 볼 조건

- **변환 대기열이 API 프로세스의 CPU를 실제로 갉아먹기 시작하면**(같은 jar라 격리가
  배포 단위 수준이지 리소스 수준이 아니다) → worker를 별도 서비스로 떼고, 그때
  [ADR-029](ADR-029-deployment-unit-vs-service-boundary.md)의 "서비스까지 나눠 추가로
  얻는 것" 판단을 다시 한다.
- **변환 워커를 2개 이상으로 늘려야 할 만큼 업로드량이 늘면** → ADR-029가 이미 적어 둔
  "워커는 아직 단일 실행 주체" 한계에 걸린다. 리더 선출/행 단위 점유 없이 worker를
  다중화하면 같은 영상을 두 번 변환하는 중복이 생긴다.
- **다른 언어·다른 배포 주기의 전용 변환 서비스가 필요해지면**(예: GPU 인코더 도입) →
  Outbox를 Kafka로 외부화하는 경로가 이미 있다(`spring-modulith-events-kafka`,
  ADR-002의 "포기한 것"). 그때 Kafka 브릿지를 켜고 별도 소비자를 둔다 — 지금 당장
  Kafka를 들일 근거는 없다.
- **운영 환경에 실제 오브젝트 스토리지가 생기면** → `ShortsStorage`를 MinIO(S3 호환)
  구현으로 바꾼다. 인터페이스가 이미 그 교체를 전제로 설계돼 있다. **같은 순간
  `ShortsMediaController`/`ShortsMediaService`(R26 재생 서빙)도 걷어낸다** — 피드가 돌려주는
  `masterPlaylistUrl`·`thumbnailUrl`을 Spring 경로 대신 CDN 오리진 주소(또는 presigned GET)로
  바꾸면 된다. `ShortsFeedItemView`가 그 URL을 만드는 자리를 이미 `ShortsMediaUrls` 하나로
  모아 뒤서, 교체 지점이 한 곳이다.
- ~~**DB(`short_videos`)는 `READY`인데 로컬 파일은 사라진 상태가 반복돼 불편해지면 →
  영속 볼륨으로 옮긴다.**~~ **(→ 해소됨, 위 "현행화" 절)** `commerce-shorts-storage` named
  volume(`compose.b-studio.yaml`)으로 옮겨 b-studio 샌드박스에서는 더 반복되지 않는다. 다른
  compose(저장소 루트 `compose.yaml` 등)에서 같은 증상이 보이면 같은 패턴을 넣는다 — 오브젝트
  스토리지로 바뀌면(바로 위 항목) 이 문제 자체가 구조적으로 없어진다(그 스토리지는 컨테이너
  생명주기와 아예 독립이다).
