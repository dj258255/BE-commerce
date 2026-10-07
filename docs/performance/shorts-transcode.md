# 숏폼 변환(R23·R24) 벤치마크: 세 화질 인코딩 방식과 60초 목표

R24는 "60초 영상의 업로드 완료부터 `READY`까지 로컬에서 60초 이내"를 요구한다. 이 문서는 그
변환 단계(세 화질 fMP4 HLS + 썸네일 + 마스터 재생목록)만 떼어, 세 가지 구현 방식 중 무엇을
고를지 숫자로 본다. 설계 비교(FFmpeg를 어디서 돌릴지 등)는 별도 설계 메모에서 다뤘다 — 이
문서는 그중 "변환 자체가 얼마나 걸리는가"만 잰다.

## 이 숫자는 "산출물이 맞다"가 확인된 숫자다

처음 버전의 스크립트는 **시간만 재고 결과물을 확인하지 않았다.** 그래서 "잰 시간"이 "제대로
된 산출물이 나온 시간"이라는 보장이 없었다 — 실제로 그 틈에서 결함 하나를 찾았다(아래 참고).
지금 스크립트는 회차마다 지우기 전에 다음을 확인하고, 하나라도 틀리면 그 회차를 `raw.csv`에
`FAIL`로 남기며 **스크립트를 즉시 0이 아닌 코드로 끝낸다**:

- 세 화질(1080x1920·720x1280·480x854) 재생목록과 세그먼트 파일이 실제로 존재하는가
- 재생목록의 세그먼트 `#EXTINF` 길이 합이 원본(60초)과 **±1초** 안에서 맞는가
- 썸네일이 비어 있지 않은가
- **마스터 재생목록**이 세 렌디션을 `RESOLUTION`·`BANDWIDTH`와 함께 참조하는가(R23 인수 조건)

## 발견한 결함(이 검증을 넣다가 찾았다)

마스터 재생목록을 만드는 함수가 `local outdir="$1" master="$outdir/master.m3u8"`처럼 한
줄에 연달아 썼다. bash는 같은 `local` 문의 뒤쪽 대입에서 쓰는 `$outdir`를 **그 local이
만들기 전의(바깥 스코프) 값**으로 먼저 치환한다 — 이 함수를 다른 함수 안에서 부르지 않고
단독으로 부르면 `outdir`가 비어 있어 `master`가 `/master.m3u8`(파일시스템 루트)로 풀렸다.

실제 벤치마크 실행에서는 호출한 쪽(`run_serial` 등)에 마침 같은 이름·같은 값의 `outdir`
지역변수가 이미 있어서 **우연히** 올바른 값으로 풀렸던 것으로 보인다 — 즉 지금까지 저장소에
적힌 "구 측정값"의 세 화질·썸네일 자체는 실제로 맞았을 가능성이 높지만, **그게 운이었다는
사실 자체가 검증이 없었다는 증거다.** `local a=1 b=$a` 같은 연쇄 대입을 두 줄로 나눠 고쳤고
(`tools/run-shorts-transcode-bench.sh`의 `write_master`), 네 가지 실패 케이스(세그먼트
누락·썸네일 비움·마스터 누락·길이 불일치)를 각각 주입해 `validate_outputs`가 실제로 잡는지
확인한 뒤 전체를 다시 쟀다.

## 무엇을 비교했나

같은 합성 소스(60초·1080x1920·30fps, `lavfi testsrc2` + 무음 오디오)를 **1080x1920(5Mbps)·
720x1280(2.5Mbps)·480x854(1Mbps)** 세 화질의 fMP4 HLS(`-hls_segment_type fmp4`) + 썸네일 +
마스터 재생목록으로 바꾸는 데 걸리는 시간을 쟀다. x264 `veryfast`·`superfast` 프리셋 각각에
대해 세 방식을 비교했다.

| id | 무엇 | 디코드 횟수 | 프로세스 수 |
|---|---|---|---|
| `serial` | 화질마다 ffmpeg를 따로 띄워 차례로 처리(가장 단순한 구현) | 3회(화질마다 1회) | 3(차례로) |
| `filtersplit` | 한 ffmpeg 프로세스가 `-filter_complex split=3`으로 한 번만 디코드하고 세 화질을 동시 출력 | 1회 | 1 |
| `parallel` | `serial`과 같은 세 프로세스를 백그라운드로 동시에 띄우고 벽시계로만 잰다 | 3회 | 3(동시) |

영상은 저장소에 넣지 않는다 — `lavfi testsrc2`로 그 자리에서 만든다(결정적, 라이선스 걱정 없음).

## 결과 (중앙값, 3회) — 검증 통과(18/18)

**샌드박스(가상화 ARM 8코어, ffmpeg 7.0.2-static)**

| 방식 | 프리셋 | 중앙값(초) | 최소~최대(초) | 60초 대비 여유(초) | 구 측정값(검증 전, 참고용) |
|---|---|---:|---:|---:|---:|
| serial | veryfast | 20.41 | 20.24~20.58 | +39.59 | 16.62 |
| serial | superfast | 15.50 | 15.04~16.00 | +44.50 | 11.86 |
| filtersplit | veryfast | 14.58 | 14.31~14.97 | +45.42 | 11.53 |
| filtersplit | superfast | 11.18 | 10.75~11.38 | +48.82 | 8.53 |
| parallel | veryfast | 17.27 | 17.27~17.39 | +42.73 | 13.22 |
| parallel | superfast | 13.09 | 12.77~13.17 | +46.91 | 10.43 |

새 값이 구 값보다 3~4초(약 25~30%) 더 걸려 처음엔 "마스터 재생목록 작성이 측정 구간에
새로 들어가서"라고 적었는데, **근거 없는 설명이었다.** 마스터 재생목록은 텍스트 9줄을
쓰는 작업이라 수 ms 수준이고, 검증(`validate_outputs`)은 측정 구간(t0~t1) **밖에서** 돈다 —
둘 다 25~30%를 설명할 크기가 아니다. 그래서 커밋 `80e51dd`의 스크립트(검증·마스터 재생목록
없음)와 지금 스크립트를 **같은 시간대에 번갈아** 돌려 확인했다(아래 "구 스크립트와 교차
비교" 절). 결론: **차이를 확인하지 못했다 — 환경 변동으로 보인다.** 교차 비교에서 구
스크립트가 오히려 한 번(48.91초) 지금 스크립트의 어떤 수치보다도 크게 튀었다. 위 표의 구
측정값 열은 단일 시점에 한 번 잰 값이라 이런 변동을 못 보여줄 뿐이다 — **구 값과 새 값의
차이를 코드 변경의 효과로 읽지 말 것.** 다만 **방식·프리셋 간 순위**는 구 값과 새 값이
똑같다(filtersplit+superfast가 항상 가장 빠르고, serial+veryfast가 항상 가장 느리다) — 이
순위는 안정적이다.

## 구 스크립트와 교차 비교 (커밋 `80e51dd` vs 지금)

"잰 시간이 늘었다"가 코드 변경(검증·마스터 재생목록 추가) 때문인지, 그냥 환경이 그 사이에
달랐던 것인지를 가르려고, 커밋 `80e51dd`의 `tools/run-shorts-transcode-bench.sh`(검증·마스터
재생목록 없음, `git`이 없어 `.git/objects`의 loose object를 직접 inflate해 복원했다)와 지금
스크립트를 **구·신·구·신·구·신** 순서로 번갈아 돌렸다. 조합마다 3회(= 양쪽 각 3회),
`DURATION=60`.

**serial / superfast**

| 순서 | 구(80e51dd) | 신(지금) |
|---|---:|---:|
| 1 | 15.72 | 16.73 |
| 2 | 48.91 | 15.55 |
| 3 | 13.75 | 13.58 |
| 중앙값 | 15.72 | 15.55 |
| 최소~최대 | 13.75~48.91 | 13.58~16.73 |

**filtersplit / superfast**

| 순서 | 구(80e51dd) | 신(지금) |
|---|---:|---:|
| 1 | 9.95 | 10.04 |
| 2 | 11.20 | 10.30 |
| 3 | 9.76 | 9.69 |
| 중앙값 | 9.95 | 10.04 |
| 최소~최대 | 9.76~11.20 | 9.69~10.30 |

**결론: 차이를 확인하지 못했다.** 두 조합 모두 구·신 중앙값이 0.2초 안에서 같다. 오히려 구
스크립트 쪽에서 48.91초짜리 극단값이 하나 나왔다 — 같은 `serial`/`superfast`를 신 스크립트는
13.58~16.73초 범위에서만 쟀는데, 검증·마스터 재생목록이 **없는** 구 스크립트가 그보다 3배
가까이 걸린 회차가 있었다는 것은 코드 차이가 원인일 수 없다는 뜻이다(구 스크립트가 할 일이
더 적은데 더 오래 걸렸다). 이 샌드박스는 가상화된 공유 CPU라 **단일 회차 하나는 3배까지도
튈 수 있다** — 원래 문서의 "새 값이 구 값보다 3~4초 늘었다"는 바로 이런 변동 범위 안에 있는
차이였다. 처음 측정과 이번 측정이 다른 시간대였다는 것 외에 다른 원인은 찾지 못했다.

**호스트(Apple M2 Pro 12코어, ffmpeg 8.0) — 참고용, 사용자 제공**

| 방식 | 프리셋 | 소요(초) |
|---|---|---:|
| filtersplit | superfast | 6.82 |
| serial | superfast | 8.84 |

샌드박스(가상화 8코어)보다 **40~50% 빠르다** — 코어 수(8 vs 12)뿐 아니라 가상화 오버헤드가
없는 실제 하드웨어라는 점, ffmpeg 버전 차이(7.0.2 vs 8.0)도 섞여 있어 정확히 무엇의 기여인지는
가르지 않았다. 다만 이 수치가 "샌드박스 숫자는 과소평가하는 쪽"이라는 방향은 분명하다 — 즉
운영 하드웨어가 샌드박스보다 느릴 걱정은 없다.

읽는 법:

- **여섯 조합 전부 60초 목표 대비 39초 이상 여유가 있다**(새 수치 기준). 가장 느린 조합
  (`serial`/`veryfast`, 20.4초)도 여유가 39.6초다.
- **`filtersplit`이 가장 빠르다.** 디코드를 한 번만 하는 이득이 실제로 보인다.
- **`parallel`은 `serial`보다 빠르지만 `filtersplit`보다는 느리다.**
- 프리셋 효과(`veryfast`→`superfast`)가 방식 효과보다 크다.

## 측정 환경

```
measured_at    : 2026-10-07T18:47:25+00:00
ffmpeg_bin     : (정적 빌드, 아래 "FFmpeg를 구한 방법" 참고)
ffmpeg_version : ffmpeg version 7.0.2-static https://johnvansickle.com/ffmpeg/
os             : Linux 6.8.0-50-generic aarch64
cpu_cores      : 8
cpu_model      : ARM implementer=0x61 part=0x000 (Apple Silicon을 가상화한 샌드박스 컨테이너)
in_container   : yes
duration_sec   : 60
reps           : 3
```

**이 수치는 이 환경에서 잰 값이다.** 다른 기계·다른 ffmpeg 빌드·실제 사용자 영상(테스트 패턴보다
복잡한 장면 전환·모션)의 수치와 직접 비교하지 말고, 같은 스크립트로 다시 재라.

## 재현

```bash
# ffmpeg가 PATH에 있어야 한다. 설치 방법은 이 스크립트가 정하지 않는다(환경마다 다르다).
./tools/run-shorts-transcode-bench.sh

# ffmpeg가 PATH 밖에 있으면
FFMPEG_BIN=/path/to/ffmpeg ./tools/run-shorts-transcode-bench.sh

# 한 번 호출 시간이 제한된 환경에서는 조합을 나눠 같은 OUT_DIR에 이어붙일 수 있다
OUT_DIR=docs/performance/runs/my-run METHODS=serial PRESETS=veryfast ./tools/run-shorts-transcode-bench.sh
OUT_DIR=docs/performance/runs/my-run METHODS=serial PRESETS=superfast ./tools/run-shorts-transcode-bench.sh
# ... 나머지 조합도 같은 OUT_DIR로
```

원자료는 `docs/performance/runs/<날짜>-shorts-transcode/`(`.gitignore` 대상) — `raw.csv`(회차별
원값, 검증 실패 회차는 `FAIL`로 표시)와 `environment.txt`, 그리고 스크립트가 다시 쓰는 `report.md`.

검증 실패를 직접 보고 싶으면(배관이 실제로 끊음을 확인):

```bash
# 세그먼트 하나를 지우고 validate_outputs만 단독으로 불러보면 즉시 실패 사유가 출력된다.
# (스크립트 안 함수라 별도 재현 스텁은 두지 않았다 — 함수 정의를 보고 같은 식으로 손으로
# 깨뜨려 보는 것이 가장 정직한 재현이다. "결함" 절에 쓴 방식과 같다.)
```

## FFmpeg를 구한 방법(샌드박스 측정 전용 — 스크립트에는 안 들어있음)

이 측정을 돌린 b-studio 샌드박스의 commerce 이미지에는 ffmpeg가 없고, `apt-get update`는
egress 프록시가 Ubuntu/Debian 미러를 `403 Forbidden`으로 막아 실패했다(`ports.ubuntu.com`,
`deb.debian.org` 모두 — 즉 **이 샌드박스에서 apt로 ffmpeg를 설치하는 길은 지금 막혀 있다**).

대신 PyPI(`files.pythonhosted.org`는 허용돼 있었다)에서 `imageio-ffmpeg` 휠을 내려받아 그 안의
johnvansickle.com **완전 정적(static) linux-aarch64 ffmpeg 7.0.2** 바이너리를 꺼내 썼다 — 의존
`.so`가 전혀 없어 추가 설치 없이 바로 실행됐다. (참고로 먼저 시도한 Maven Central의
`org.bytedeco:ffmpeg`는 받아지긴 했지만 `libdrm.so.2` 등 시스템 공유 라이브러리가 없어
실행이 안 됐다 — 동적 링크 바이너리라 시스템 라이브러리에 다시 의존한다.)

```bash
curl -s https://pypi.org/pypi/imageio-ffmpeg/json -o meta.json
URL=$(grep -o '"url":"[^"]*manylinux2014_aarch64\.whl"' meta.json | tail -1 | cut -d'"' -f4)
curl -sL -o imageio_ffmpeg.whl "$URL"
jar xf imageio_ffmpeg.whl imageio_ffmpeg/binaries/ffmpeg-linux-aarch64-v7.0.2
mv imageio_ffmpeg/binaries/ffmpeg-linux-aarch64-v7.0.2 ffmpeg && chmod +x ffmpeg
```

**이건 이번 측정만을 위한 임시 조치다.** 운영 `Dockerfile`이나 샌드박스의 `Dockerfile.b-studio`는
바꾸지 않았다 — ffmpeg를 실제로 어디에 설치하고 어떤 배포 단위로 돌릴지는 별도 설계 결정
사항이다. 이 벤치마크 스크립트 자체는 `FFMPEG_BIN`(또는 PATH)으로 주어진 ffmpeg가 있다고만
전제하며, 설치 방법은 정하지 않는다(`tools/bench.sh`가 k6 설치를 전제만 하는 것과 같은 원칙).

## 이 측정이 말하지 않는 것

- **합성 테스트 패턴(`testsrc2`)은 실제 상품 숏폼보다 단순할 수 있다.** 장면 전환이 잦거나
  모션이 복잡한 실제 영상은 x264가 더 느리게 인코딩할 수 있다 — 이 숫자를 실제 업로드
  영상의 상한으로 쓰지 말 것.
- **가상화된 공유 CPU에서 잰 샌드박스 값이다.** 호스트(M2 Pro) 참고값이 40~50% 더 빠른
  것으로 봐도, 운영 환경이 샌드박스보다 느릴 걱정은 적다고 본다.
- **오디오 트랙은 무음(anullsrc)이다.** 실제 음성·음악 오디오의 AAC 인코딩 비용은 거의
  무시할 수준이라 결과에 큰 영향은 없을 것으로 본다(측정하지 않음).
- **코어 수가 다른 기계(특히 2~4코어)는 `parallel`의 이득이 줄거나 손해로 바뀔 수 있다.**
- **이 샌드박스에서 단일 회차는 최대 3배까지 튄 적이 있다**(교차 비교의 48.91초). 메인 표의
  최소~최대는 3회 기준이라 이 정도 극단값까지는 못 보여줄 수 있다 — 여유가 수십 초로 크므로
  결론이 흔들릴 정도는 아니라고 보지만, 중요한 결정에는 더 많은 회차로 다시 재는 쪽이 안전하다.
- **검증은 길이·존재·비어있지 않음만 본다.** 세그먼트의 실제 화질(해상도가 요청대로
  나왔는지 픽셀 단위로, 또는 디코드해서 깨짐이 없는지)까지는 확인하지 않는다 — ffprobe가
  없는 환경에서도 돌아야 해서 재생목록이 보장하는 선(길이·구조)까지만 본다.

## R24: 업로드 완료 → READY(실제 경로)

위 숫자는 전부 "FFmpeg 변환만" 떼어 쟀다. R24가 실제로 요구하는 것은 **업로드 완료부터
`READY`까지**다 — 그 사이에는 변환 말고도 Outbox 이벤트 발행·커밋, `@ApplicationModuleListener`
비동기 핸드오프, probe(메타 점검) 단계가 더 있다(R23 2·3단계). 이 절은 그 전체를 실제
업로드 API로 재서, 변환 단독 시간과 실제 경로 사이에 숨은 비용이 있는지 본다.

### 트랜잭션 경계를 고치고 나서 다시 쟀다

첫 측정(아래 "이전 측정" 참고)은 폴링으로 `UPLOADED`→`READY`만 보였다 — `PROBING`·
`TRANSCODING`은 한 번도 관측되지 않았다. 원인은 `ShortsTranscodeService.processUploaded`가
`@Transactional` 메서드 하나였다는 것이다: probe→transcode→기록 전체가 **하나의 DB
트랜잭션**으로 묶여 FFmpeg가 도는 10초 넘는 동안 DB 커넥션을 붙잡고 있었고, 중간 상태도
그 트랜잭션이 끝나야 커밋돼 밖에서 안 보였다. 상태 전이마다 짧은 트랜잭션으로 커밋하고
FFmpeg는 트랜잭션 밖에서 돌도록 고쳤다(`ShortVideoTransitionService`, 코드 변경 상세는
커밋 로그 참고). 그런데 고친 뒤에도 폴링은 여전히 `UPLOADED`→`READY`만 보였다 —
**두 번째 원인**이 남아 있었다: Spring Modulith의 `@ApplicationModuleListener`는
`@Async` + `@Transactional(propagation = REQUIRES_NEW)` + `@TransactionalEventListener`를
합성한 애너테이션이다(`javap -v`로 클래스 파일을 직접 열어 확인했다) — 즉 리스너 메서드
자체가 **이미 새 트랜잭션에 들어간 채로** 시작돼, 그 안에서 부르는 짧은 트랜잭션들(기본
전파 REQUIRED)이 전부 그 바깥 트랜잭션에 합류해 버렸다. 리스너 메서드에
`@Transactional(propagation = NOT_SUPPORTED)`를 추가로 선언해 그 바깥 트랜잭션을
꺼야(suspend) 비로소 하위 전이들이 진짜 독립적인 짧은 트랜잭션으로 커밋됐다
(`ShortsTranscodeListener`). 이 두 번째 원인은 실제 MySQL에 대한 반복 폴링과(같은 애플리케이션
커넥션 풀), **완전히 새로운 JDBC 연결로 직접 쿼리해도** 똑같이 재현됐다 — 즉 커넥션 풀이나
격리 수준 캐싱 문제가 아니라 DB에 정말로 그 시점까지 아무것도 커밋되지 않은 것이었다. 고친
뒤에는 실제 HTTP 폴링에서 `PROBING`·`TRANSCODING`이 또렷하게 보인다(아래 결과).

이 발견은 `ShortVideoTransitionBoundaryTest`(media)에 테스트로 남겼다 — 리스너를 실제로
`REQUIRES_NEW` 트랜잭션 안에서 호출해(`TransactionTemplate`로 재현) `NOT_SUPPORTED`가 없으면
어떤 일이 생기는지, 있으면 어떻게 고쳐지는지를 코드로 고정했다.

### 어떻게 쟀나

`tools/run-shorts-upload-to-ready-bench.sh`가 commerce를 **worker 프로파일**
(`SPRING_PROFILES_ACTIVE=worker`, 변환 리스너 켜짐)로 띄운 상태에서:

1. 로그인(판매자 데모 계정) → 업로드 시작 API(`POST /api/v1/shorts`)로 presigned URL을 받는다.
2. 60초·1080x1920·30fps 세로 합성 영상(`lavfi testsrc2` + 오디오, R21 상한과 같은 모양)을
   그 저장소 경로(로컬 파일, ADR-081)에 실제로 쓴다.
3. 업로드 완료 API(`POST /api/v1/shorts/{id}/complete`)를 부른다 — 이 응답의 `updatedAt`이
   `UPLOADED` 전이 시각이다(ShortVideo가 상태 전이마다 기록하는 값을 API가 그대로 노출한다 —
   폴링이 만든 값이 아니다).
4. `READY`가 될 때까지 조회 API(`GET /api/v1/shorts/{id}`)를 0.3초 간격으로 폴링한다. 상태가
   바뀔 때마다(`UPLOADED`→`PROBING`→`TRANSCODING`→`READY`) 그 응답의 `updatedAt`을 기록한다
   — 트랜잭션 경계를 고친 뒤로는 이 중간 상태들이 실제로 폴링에 잡힌다.
5. `UPLOADED`~`READY` 전체 경과, 그리고 단계별(구간) 경과를 함께 낸다. 동시에 1건만
   처리한다(순차 3회).

### 결과 (3회, 동시 변환 1건) — 고친 뒤

| rep | short_video_id | 전체 경과(초) |
|---|---|---:|
| 1 | 23 | 11.762 |
| 2 | 24 | 11.766 |
| 3 | 25 | 11.642 |

- **중앙값 11.762초, 최소~최대 11.642~11.766초**
- **60초 목표 대비 여유(중앙값 기준): +48.24초**
- **3회 모두 성공(`READY` 도달), 실패·타임아웃 0건 — R24를 통과한다.**

**단계별 분해(세 회차 모두 네 상태 전이가 전부 폴링에 잡혔다)**:

| 구간 | rep1 | rep2 | rep3 | 중앙값(초) |
|---|---:|---:|---:|---:|
| UPLOADED → PROBING (리스너 핸드오프·claim) | 0.009 | 0.007 | 0.006 | 0.007 |
| PROBING → TRANSCODING (probe, 전체 디코드) | 1.562 | 1.667 | 1.553 | 1.562 |
| TRANSCODING → READY (transcode, filtersplit+superfast) | 10.190 | 10.092 | 10.083 | 10.092 |
| **합계** | 11.761 | 11.766 | 11.642 | — |

(원자료: `docs/performance/runs/r24c-final-phased/raw.csv`·`phases.csv`, `.gitignore` 대상)

**읽는 법**:

- 전체 시간의 **약 86%(10.09초/11.76초)가 TRANSCODING**(실제 FFmpeg 인코딩)이고, **약
  13%(1.56초)가 PROBING**(전체 디코드 한 번)이다. **리스너 핸드오프(커밋 후 비동기로 넘어가
  첫 claim이 성공하기까지)는 7~9ms로 무시할 수준**이다 — Outbox·`@Async` 오버헤드가 두려워할
  크기가 아니라는 뜻이다.
- 세 회차의 단계별 값이 서로 매우 가깝다(PROBING 1.55~1.67초, TRANSCODING 10.08~10.19초) —
  트랜잭션 경계를 고치기 전 "변환 단독 시간과 실제 경로 시간의 차이가 변동 범위와 같은
  자릿수라 분해하지 못한다"고 적었던 한계가 이번에는 사라졌다. 같은 요청의 진짜 구간을
  직접 쟀기 때문이다(서로 다른 세션의 값을 맞세운 추정이 아니다).
- `docs/performance/runs/r24-transcode-only-crosscheck`(같은 세션, 독립 측정)의
  filtersplit+superfast 단독 값(8.86~9.35초, 중앙값 8.98초)과 비교하면 이번 TRANSCODING
  구간(10.08~10.19초)이 **약 1.1~1.2초 더 걸렸다** — 두 측정이 완전히 동시에 돈 것은 아니라서
  (CPU 상태가 다를 수 있다) 정밀 비교는 아니지만, 자릿수는 맞다.

### 이전 측정(트랜잭션 경계를 고치기 전) — 참고용, 틀린 가정이 있었다

트랜잭션 경계를 고치기 **전**에 같은 스크립트로 쟀을 때는 전체 경과가 11.6~11.9초로
비슷했지만(11.637~11.935초, 중앙값 11.846초), 단계별 분해는 전혀 안 보였다 — 폴링이
`UPLOADED`에서 곧장 `READY`로 건너뛰는 것만 관측했다. 그때는 이것을 "트랜잭션 경계가 중간
상태를 가린다"는 구조적 사실로 올바르게 진단했지만, 원인을 "`processUploaded`가
`@Transactional` 메서드 하나라서"로만 적었다 — **그건 원인의 절반이었다.** 그 메서드를
쪼갠 뒤에도(이 R32 턴의 1차 커밋) 증상이 똑같이 재현돼, `@ApplicationModuleListener`의
합성 `@Transactional(REQUIRES_NEW)`라는 두 번째 원인을 찾아 고쳤다(위 절 참고). 전체 경과
숫자 자체는 두 측정이 비슷해 "R24를 통과한다"는 결론은 바뀌지 않았지만, **"왜 그런 숫자가
나오는지"에 대한 이전 설명(probe+transcode 단독값을 더해 간접 추정)은 이제 더 정확한
직접 측정으로 대체됐다.**

### 측정 환경

```
measured_at    : 2026-10-07T22:02:53+00:00
base_url       : http://localhost:8080 (commerce, SPRING_PROFILES_ACTIVE=worker)
ffmpeg_version : ffmpeg version 8.0.1-3ubuntu2 (studio.yaml의 systemPackages: [ffmpeg]로 설치)
os             : Linux 6.8.0-50-generic aarch64
cpu_cores      : 8
duration_sec   : 60
reps           : 3
poll_interval  : 0.3초
```

이 수치도 위 섹션들과 같은 샌드박스(가상화 8코어)에서 쟀다 — 같은 "다른 기계와 직접 비교하지
말 것" 주의가 적용된다.

### 재현

```bash
# commerce를 worker 프로파일로 띄운 상태에서(SPRING_PROFILES_ACTIVE=worker)
./tools/run-shorts-upload-to-ready-bench.sh

# 여러 번 나눠 이어붙이기(같은 OUT_DIR), 폴링 간격을 좁혀 중간 상태를 더 잘 잡기
OUT_DIR=docs/performance/runs/my-run REPS=1 POLL_INTERVAL_SECONDS=0.3 ./tools/run-shorts-upload-to-ready-bench.sh
```

원자료는 `docs/performance/runs/<이름>/`(`.gitignore` 대상) — `raw.csv`(회차별 UPLOADED·READY
시각과 전체 경과), `phases.csv`(폴링이 관측한 모든 상태 변화와 그 시각 — 트랜잭션 경계를
고친 뒤로는 PROBING·TRANSCODING 행도 담긴다), `environment.txt`, `report.md`(단계별 경과 표를
포함).

### 이 측정이 말하지 않는 것

- **동시 변환 1건만 쟀다.** 여러 영상이 동시에 올라와 변환이 큐에 쌓이는 상황(worker가
  순차로 처리하며 뒤의 영상이 기다리는 시간)은 재지 않았다 — ADR-081의 "다시 볼 조건"
  (워커 다중화·대기열) 대상이다.
- **합성 테스트 패턴, 가상화 공유 CPU라는 한계는 위 섹션들과 같다.**
- **로그인·presigned URL 발급·로컬 파일 쓰기 같은 스크립트 자체의 오버헤드는 측정 구간
  (UPLOADED 응답 이후)에 들어가지 않는다** — "업로드 완료"가 R24의 시작점이라 그 이전 단계는
  이 숫자에 포함될 필요가 없다(요구사항 문구와 일치).
- **커넥션 풀 고갈 자체(동시 변환 여러 건이 실제로 주문·결제 요청을 막는지)는 이번에도
  별도로 재지 않았다** — 이번 수정으로 각 트랜잭션이 밀리초 단위로 짧아졌다는 것은 코드와
  테스트로 확인했지만, "짧아진 트랜잭션이 실제 동시 부하에서 커넥션 풀 고갈을 막는가"는
  k6 등으로 별도 실측해야 하는 다른 질문이다.
