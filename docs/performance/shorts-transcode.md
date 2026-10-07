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

| 방식 | 프리셋 | 중앙값(초) | 60초 대비 여유(초) | 구 측정값(검증 전, 참고용) |
|---|---|---:|---:|---:|
| serial | veryfast | 20.41 | +39.59 | 16.62 |
| serial | superfast | 15.50 | +44.50 | 11.86 |
| filtersplit | veryfast | 14.58 | +45.42 | 11.53 |
| filtersplit | superfast | 11.18 | +48.82 | 8.53 |
| parallel | veryfast | 17.27 | +42.73 | 13.22 |
| parallel | superfast | 13.09 | +46.91 | 10.43 |

새 값이 구 값보다 **3~4초씩 더 걸린다.** 마스터 재생목록 작성이 측정 구간 안에 새로 들어간
것(이전엔 애초에 엉뚱한 위치에 쓰고 있었으니 사실상 "안 재던" 작업이었다)과 공유 가상화
CPU의 회차 간 변동이 섞인 결과로 보이며, 둘을 분리해서 재지는 않았다. **방식·프리셋 간
순위는 구 값과 새 값이 똑같다**(filtersplit+superfast가 항상 가장 빠르고, serial+veryfast가
항상 가장 느리다) — 이번 수정이 상대 비교의 결론을 바꾸지는 않았다.

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
- **검증은 길이·존재·비어있지 않음만 본다.** 세그먼트의 실제 화질(해상도가 요청대로
  나왔는지 픽셀 단위로, 또는 디코드해서 깨짐이 없는지)까지는 확인하지 않는다 — ffprobe가
  없는 환경에서도 돌아야 해서 재생목록이 보장하는 선(길이·구조)까지만 본다.
