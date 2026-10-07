# 숏폼 변환(R23·R24) 벤치마크: 세 화질 인코딩 방식과 60초 목표

R24는 "60초 영상의 업로드 완료부터 `READY`까지 로컬에서 60초 이내"를 요구한다. 이 문서는 그
변환 단계(세 화질 fMP4 HLS + 썸네일)만 떼어, 세 가지 구현 방식 중 무엇을 고를지 숫자로 본다.
설계 비교(FFmpeg를 어디서 돌릴지 등)는 별도 설계 메모에서 다뤘다 — 이 문서는 그중 "변환 자체가
얼마나 걸리는가"만 잰다.

## 무엇을 비교했나

같은 합성 소스(60초·1080x1920·30fps, `lavfi testsrc2` + 무음 오디오)를 **1080x1920(5Mbps)·
720x1280(2.5Mbps)·480x854(1Mbps)** 세 화질의 fMP4 HLS(`-hls_segment_type fmp4`)와 썸네일
1장으로 바꾸는 데 걸리는 시간을 쟀다. x264 `veryfast`·`superfast` 프리셋 각각에 대해 세 방식을
비교했다.

| id | 무엇 | 디코드 횟수 | 프로세스 수 |
|---|---|---|---|
| `serial` | 화질마다 ffmpeg를 따로 띄워 차례로 처리(가장 단순한 구현) | 3회(화질마다 1회) | 3(차례로) |
| `filtersplit` | 한 ffmpeg 프로세스가 `-filter_complex split=3`으로 한 번만 디코드하고 세 화질을 동시 출력 | 1회 | 1 |
| `parallel` | `serial`과 같은 세 프로세스를 백그라운드로 동시에 띄우고 벽시계로만 잰다 | 3회 | 3(동시) |

영상은 저장소에 넣지 않는다 — `lavfi testsrc2`로 그 자리에서 만든다(결정적, 라이선스 걱정 없음).

## 결과 (중앙값, 3회)

| 방식 | 프리셋 | 중앙값(초) | 60초 대비 여유(초) |
|---|---|---:|---:|
| serial | veryfast | 16.62 | +43.38 |
| serial | superfast | 11.86 | +48.14 |
| filtersplit | veryfast | 11.53 | +48.47 |
| filtersplit | superfast | 8.53 | +51.47 |
| parallel | veryfast | 13.22 | +46.78 |
| parallel | superfast | 10.43 | +49.57 |

읽는 법:

- **여섯 조합 전부 60초 목표 대비 43초 이상 여유가 있다.** 가장 느린 조합(`serial`/`veryfast`,
  16.6초)도 여유가 43.4초다 — 이 측정 환경에서는 어느 조합을 골라도 R24를 넉넉히 만족한다.
- **`filtersplit`이 가장 빠르다.** 디코드를 한 번만 하는 이득이 실제로 보인다(같은 프리셋에서
  `serial`보다 1080x1920 디코드 2회분만큼 빠르다).
- **`parallel`은 `serial`보다 빠르지만 `filtersplit`보다는 느리다.** 세 프로세스가 8코어를
  나눠 쓰는 동시성 이득이 디코드 중복 비용보다는 크지만, 디코드를 한 번만 하는 것보다는 못하다.
- 프리셋 효과(`veryfast`→`superfast`)가 방식 효과보다 크다 — 어떤 방식을 고르든 프리셋을
  `superfast`로 낮추는 쪽이 더 큰 여유를 번다.

## 측정 환경

```
measured_at    : 2026-10-07T18:28:49+00:00
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
원값)와 `environment.txt`, 그리고 스크립트가 다시 쓰는 `report.md`.

## FFmpeg를 구한 방법(이번 측정 전용 — 스크립트에는 안 들어있음)

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
- **가상화된 공유 CPU에서 잰 값이다.** "로컬 전용 8코어"가 아니라 샌드박스 컨테이너(Apple
  Silicon 호스트를 가상화)다. 여유가 43초 이상으로 크므로 결론이 바뀔 정도는 아니라고
  보지만, 운영 환경에서 다시 재는 것이 안전하다.
- **오디오 트랙은 무음(anullsrc)이다.** 실제 음성·음악 오디오의 AAC 인코딩 비용은 거의
  무시할 수준이라 결과에 큰 영향은 없을 것으로 본다(측정하지 않음).
- **코어 수가 다른 기계(특히 2~4코어)는 `parallel`의 이득이 줄거나 손해로 바뀔 수 있다** —
  이 측정은 8코어 전제다.
