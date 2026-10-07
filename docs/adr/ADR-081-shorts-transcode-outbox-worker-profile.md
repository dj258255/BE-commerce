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
  구현으로 바꾼다. 인터페이스가 이미 그 교체를 전제로 설계돼 있다.
