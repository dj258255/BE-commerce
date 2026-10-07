#!/usr/bin/env bash
#
# 숏폼 변환(R23) 벤치마크 — 60초·1080x1920·9:16 합성 영상을 세 화질
# (1080x1920/5Mbps, 720x1280/2.5Mbps, 480x854/1Mbps) fMP4 HLS + 썸네일로 바꾸는 데
# 걸리는 시간을 재서 docs/performance/에 표로 남긴다. R24 목표(업로드 완료→READY 60초 이내)
# 대비 여유를 숫자로 본다.
#
# 비교하는 세 방식:
#   serial      — 화질마다 ffmpeg 프로세스를 따로 띄워 차례로 디코드+인코드(가장 단순)
#   filtersplit — 입력을 한 번만 디코드하고(-filter_complex split) 한 프로세스에서 세 화질을 동시 출력
#   parallel    — serial과 같은 세 프로세스를 백그라운드로 동시에 띄우고 벽시계로만 잰다
# 프리셋 veryfast·superfast 두 가지 × 위 세 방식 = 6개 조합, 조합마다 REPS회(기본 3) 재
# 중앙값을 쓴다(한 번 재서 운 좋은/나쁜 값을 보고하지 않으려고).
#
# 전제: ffmpeg가 PATH에 있어야 한다. 설치 방법은 이 스크립트가 정하지 않는다 — 환경마다 다르다
# (bench.sh가 k6 설치를 요구만 하고 설치 방법을 안 정하는 것과 같은 이유). 못 찾으면 바로 멈춘다.
#
#   ./tools/run-shorts-transcode-bench.sh
#   FFMPEG_BIN=/path/to/ffmpeg ./tools/run-shorts-transcode-bench.sh
#   REPS=5 DURATION=60 ./tools/run-shorts-transcode-bench.sh
#
# 한 번 호출이 몇 분 안으로 제한된 환경(예: 일부 샌드박스)에서는 METHODS·PRESETS로 조합을
# 나누고 OUT_DIR을 고정해 여러 번 이어서 부를 수 있다(같은 raw.csv에 이어붙고, 매번 그때까지의
# report.md를 다시 쓴다):
#   OUT_DIR=docs/performance/runs/2026-x METHODS=serial   PRESETS=veryfast ./tools/run-shorts-transcode-bench.sh
#   OUT_DIR=docs/performance/runs/2026-x METHODS=serial   PRESETS=superfast ./tools/run-shorts-transcode-bench.sh
#   OUT_DIR=docs/performance/runs/2026-x METHODS=filtersplit ...
#
# python3 없이 awk로 집계한다 — 이 스크립트가 돌아야 하는 환경 중 하나(b-studio 샌드박스의
# commerce 컨테이너)에 python3가 없다. awk는 거의 모든 환경에 있다.
set -euo pipefail

FFMPEG_BIN="${FFMPEG_BIN:-ffmpeg}"
REPS="${REPS:-3}"
DURATION="${DURATION:-60}"
# 전체 조합(3방식×2프리셋×REPS회)이 한 번 실행으로 몇 분을 넘길 수 있어, 샌드박스처럼 한 호출의
# 실행 시간이 제한된 환경에서는 METHODS/PRESETS로 조합을 나눠 여러 번 부르고 같은 OUT_DIR에
# 이어붙일 수 있게 한다. 기본은 전체 조합을 한 번에 돈다(로컬 개발 머신 등).
METHODS="${METHODS:-serial filtersplit parallel}"
PRESETS="${PRESETS:-veryfast superfast}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [ -n "${OUT_DIR:-}" ]; then
  mkdir -p "$OUT_DIR"
else
  STAMP="$(date +%Y%m%d-%H%M%S)"
  OUT_DIR="$ROOT/docs/performance/runs/$STAMP-shorts-transcode"
fi
SCRATCH="$(mktemp -d)"
trap 'rm -rf "$SCRATCH"' EXIT

log() { printf '\033[36m▶\033[0m %s\n' "$*"; }
ok()  { printf '\033[32m✓\033[0m %s\n' "$*"; }
die() { printf '\033[31m✗\033[0m %s\n' "$*" >&2; exit 1; }

command -v "$FFMPEG_BIN" >/dev/null 2>&1 || die \
  "ffmpeg를 찾을 수 없습니다 (\$FFMPEG_BIN=$FFMPEG_BIN). PATH에 넣거나 FFMPEG_BIN=/path/to/ffmpeg로 지정하세요."

mkdir -p "$OUT_DIR"
RAW_CSV="$OUT_DIR/raw.csv"
[ -f "$RAW_CSV" ] || echo "method,preset,rep,seconds" > "$RAW_CSV"

# ── 0. 측정 환경 기록 — 숫자는 환경과 함께여야 의미가 있다(bench.sh와 같은 원칙) ──
ENV_FILE="$OUT_DIR/environment.txt"
{
  echo "measured_at    : $(date -Iseconds)"
  echo "ffmpeg_bin     : $FFMPEG_BIN"
  echo "ffmpeg_version : $("$FFMPEG_BIN" -version 2>&1 | head -1)"
  echo "os             : $(uname -srm)"
  echo "cpu_cores      : $(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo unknown)"
  if [ -f /proc/cpuinfo ]; then
    # grep이 못 찾으면 1을 돌려주는데, set -e -o pipefail 아래서는 그게 스크립트 전체를
    # 중단시킨다(ARM에는 "model name" 줄이 없어 실제로 터졌다) — 매 grep에 `|| true`로 막는다.
    model="$(grep -m1 'model name' /proc/cpuinfo | cut -d: -f2- | sed 's/^ *//' || true)"
    # x86은 "model name"이 있지만 ARM(aarch64)은 없다 — implementer/part로 대신 적는다
    # (0x61=Apple인 경우가 많다, Apple Silicon을 colima 등으로 가상화했을 때).
    if [ -z "$model" ]; then
      impl="$(grep -m1 'CPU implementer' /proc/cpuinfo | cut -d: -f2- | sed 's/^ *//' || true)"
      part="$(grep -m1 'CPU part' /proc/cpuinfo | cut -d: -f2- | sed 's/^ *//' || true)"
      model="ARM implementer=$impl part=$part"
    fi
    echo "cpu_model      : $model"
  fi
  echo "in_container   : $([ -f /.dockerenv ] && echo yes || echo "no(또는 판별 불가)")"
  echo "duration_sec   : $DURATION"
  echo "reps           : $REPS"
} > "$ENV_FILE"
ok "환경 기록 → ${ENV_FILE#"$ROOT"/}"

# ── 1. 합성 소스 — R21 업로드 상한과 같은 모양(60초·1080x1920·9:16) ──
# 영상은 repo에 넣지 않는다 — lavfi testsrc2로 그 자리에서 만든다(결정적 패턴, 라이선스 걱정 없음).
# ${TMPDIR:-/tmp}에 길이별로 캐싱한다 — METHODS/PRESETS로 조합을 나눠 여러 번 부를 때마다
# 같은 60초 소스를 다시 만들면(그 자체로 몇 초~수십 초) 호출마다 시간이 아깝다.
SRC="${TMPDIR:-/tmp}/shorts-bench-src-${DURATION}s.mp4"
if [ -f "$SRC" ]; then
  ok "합성 소스 재사용: $SRC ($(du -h "$SRC" | cut -f1))"
else
  log "합성 소스 생성 (lavfi testsrc2, ${DURATION}초 1080x1920@30fps + 무음 오디오)"
  "$FFMPEG_BIN" -hide_banner -loglevel error -y \
    -f lavfi -i "testsrc2=size=1080x1920:rate=30:duration=$DURATION" \
    -f lavfi -i "anullsrc=r=44100:cl=stereo" \
    -shortest -c:v libx264 -preset veryfast -c:a aac -b:a 128k \
    "$SRC"
  ok "합성 소스: $(du -h "$SRC" | cut -f1)"
fi

LABELS=(1080 720 480)
SCALES=(1080:1920 720:1280 480:854)
BITRATES=(5M 2.5M 1M)

# 화질 하나를 fMP4 HLS로. $1=프리셋 $2=출력 디렉터리 $3=인덱스(0/1/2 → LABELS/SCALES/BITRATES)
encode_one() {
  local preset="$1" outdir="$2" i="$3"
  local label="${LABELS[$i]}" scale="${SCALES[$i]}" bitrate="${BITRATES[$i]}"
  mkdir -p "$outdir/$label"
  "$FFMPEG_BIN" -hide_banner -loglevel error -y -i "$SRC" \
    -vf "scale=$scale" -c:v libx264 -preset "$preset" -b:v "$bitrate" -maxrate "$bitrate" -bufsize "$bitrate" \
    -g 60 -c:a aac -b:a 128k \
    -f hls -hls_time 4 -hls_playlist_type vod -hls_segment_type fmp4 \
    "$outdir/$label/out.m3u8"
}

thumbnail() {
  local outdir="$1"
  "$FFMPEG_BIN" -hide_banner -loglevel error -y -i "$SRC" -vframes 1 -vf "scale=480:-1" "$outdir/thumb.jpg"
}

run_serial() {
  local preset="$1" outdir="$2"
  for i in 0 1 2; do encode_one "$preset" "$outdir" "$i"; done
  thumbnail "$outdir"
}

# 한 번만 디코드하고(split=3) 세 화질을 한 ffmpeg 프로세스가 동시에 출력한다.
# 주의: 각 -map 쌍(영상 1개+오디오 1개)이 그 뒤 출력 파일 하나에 대응하므로 코덱 옵션에
# 스트림 인덱스(:0 :1 ...)를 안 붙여도 된다 — 출력마다 "그 출력의 0번째" 영상/오디오로 풀린다.
run_filtersplit() {
  local preset="$1" outdir="$2"
  mkdir -p "$outdir/1080" "$outdir/720" "$outdir/480"
  "$FFMPEG_BIN" -hide_banner -loglevel error -y -i "$SRC" \
    -filter_complex "[0:v]split=3[v1][v2][v3];[v1]scale=1080:1920[v1o];[v2]scale=720:1280[v2o];[v3]scale=480:854[v3o]" \
    -map "[v1o]" -map 0:a -c:v libx264 -preset "$preset" -b:v 5M -maxrate 5M -bufsize 5M -g 60 -c:a aac -b:a 128k \
      -f hls -hls_time 4 -hls_playlist_type vod -hls_segment_type fmp4 "$outdir/1080/out.m3u8" \
    -map "[v2o]" -map 0:a -c:v libx264 -preset "$preset" -b:v 2.5M -maxrate 2.5M -bufsize 2.5M -g 60 -c:a aac -b:a 128k \
      -f hls -hls_time 4 -hls_playlist_type vod -hls_segment_type fmp4 "$outdir/720/out.m3u8" \
    -map "[v3o]" -map 0:a -c:v libx264 -preset "$preset" -b:v 1M -maxrate 1M -bufsize 1M -g 60 -c:a aac -b:a 128k \
      -f hls -hls_time 4 -hls_playlist_type vod -hls_segment_type fmp4 "$outdir/480/out.m3u8"
  thumbnail "$outdir"
}

run_parallel() {
  local preset="$1" outdir="$2"
  local pids=()
  for i in 0 1 2; do
    encode_one "$preset" "$outdir" "$i" &
    pids+=("$!")
  done
  thumbnail "$outdir" &
  pids+=("$!")
  local fail=0
  for p in "${pids[@]}"; do wait "$p" || fail=1; done
  [ "$fail" -eq 0 ] || die "parallel 인코딩 중 하나가 실패했습니다"
}

now() { date +%s.%N 2>/dev/null || date +%s; }

measure() {
  local method="$1" preset="$2" rep="$3"
  local outdir="$SCRATCH/$method-$preset-$rep"
  local t0 t1 elapsed
  t0="$(now)"
  case "$method" in
    serial)      run_serial "$preset" "$outdir" ;;
    filtersplit) run_filtersplit "$preset" "$outdir" ;;
    parallel)    run_parallel "$preset" "$outdir" ;;
    *) die "알 수 없는 방식: $method" ;;
  esac
  t1="$(now)"
  elapsed="$(awk -v a="$t0" -v b="$t1" 'BEGIN{printf "%.2f", b-a}')"
  rm -rf "$outdir"
  echo "$method,$preset,$rep,$elapsed" >> "$RAW_CSV"
  printf '%s\n' "$elapsed"
}

for method in $METHODS; do
  for preset in $PRESETS; do
    log "측정: $method / $preset (${REPS}회)"
    for rep in $(seq 1 "$REPS"); do
      t="$(measure "$method" "$preset" "$rep")"
      echo "  rep $rep: ${t}s"
    done
  done
done
ok "원자료 → ${RAW_CSV#"$ROOT"/}"

# ── 보고서 — awk로 조합별 중앙값 + 60초 대비 여유를 표로 뽑는다 ──
TABLE="$SCRATCH/table.md"
awk -F, -v budget=60 '
  NR==1 { next }
  {
    key=$1 SUBSEP $2
    n[key]++
    vals[key SUBSEP n[key]] = $4
    if (!($1 in seenM)) { seenM[$1]=1; morder[++mc]=$1 }
    if (!($2 in seenP)) { seenP[$2]=1; porder[++pc]=$2 }
  }
  END {
    print "| 방식 | 프리셋 | 중앙값(초) | 60초 대비 여유(초) |"
    print "|---|---|---:|---:|"
    for (mi=1; mi<=mc; mi++) { m=morder[mi]
      for (pi=1; pi<=pc; pi++) { p=porder[pi]
        key=m SUBSEP p
        cnt=n[key]
        if (cnt=="") continue
        for (i=1;i<=cnt;i++) a[i]=vals[key SUBSEP i]+0
        for (i=1;i<=cnt;i++) for (j=i+1;j<=cnt;j++) if (a[j]<a[i]) { t=a[i]; a[i]=a[j]; a[j]=t }
        med = a[int((cnt+1)/2)]
        margin = budget - med
        printf "| %s | %s | %.2f | %+.2f |\n", m, p, med, margin
        delete a
      }
    }
  }
' "$RAW_CSV" > "$TABLE"

REPORT="$OUT_DIR/report.md"
{
  echo "# 숏폼 변환(R23) 벤치마크"
  echo
  echo "측정 시각: $(date -Iseconds)"
  echo
  echo "60초·1080x1920·30fps 세로 합성 영상을 1080x1920(5Mbps)·720x1280(2.5Mbps)·480x854(1Mbps)"
  echo "fMP4 HLS + 썸네일로 바꾸는 데 걸린 시간이다(합성 소스 생성 시간은 제외, 변환만 잰다)."
  echo "각 조합 ${REPS}회 측정의 **중앙값**. R24 목표: 업로드 완료부터 READY까지 60초 이내."
  echo
  cat "$TABLE"
  echo
  echo "## 측정 환경"
  echo
  echo '```'
  cat "$ENV_FILE"
  echo '```'
  echo
  echo "## 원자료"
  echo
  echo "모든 회차의 개별 측정값: \`raw.csv\`(같은 디렉터리)."
  echo
  echo "## 읽을 때 주의"
  echo
  echo "- 이 수치는 위 \"측정 환경\"에서 잰 값이다. 다른 기계·다른 ffmpeg 빌드의 수치와 직접 비교하지 말고, 같은 스크립트로 다시 재라(bench.sh와 같은 원칙)."
  echo "- \`filtersplit\`은 디코드를 한 번만 하므로 CPU 총 사용량은 가장 적지만, 벽시계 시간이 가장 짧다는 뜻은 아니다(세 인코더가 한 프로세스 안에서 코어를 나눠 쓴다)."
  echo "- \`parallel\`은 세 프로세스가 동시에 8코어를 나눠 쓰므로 코어가 적은 기계에서는 serial보다 느릴 수 있다."
} > "$REPORT"

cat "$REPORT"
echo
ok "리포트: ${REPORT#"$ROOT"/}"
