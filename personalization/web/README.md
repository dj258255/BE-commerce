# 개인화 프론트 (web)

개인화 홈과 실험 콘솔. **백엔드 없이 목 계약으로 동작한다.**

## 실행

```bash
./serve.sh          # http://localhost:8088
./serve.sh 9000     # 포트 지정
```

빌드 도구가 없다. 정적 파일이라 `python3 -m http.server`면 충분하다.

## 화면

| 파일 | 무엇 |
|---|---|
| `index.html` | 개인화 홈 — 구성 출처·신선도·지연 분해·행 구성 |
| `console.html` | 실험 콘솔 — 여섯 실험의 상태와 결과 |
| `exp-cache.html` | 캐시 압축 임계값 — 차트·원자료·트레이드오프 |

## 목(MOCK) 규칙

이 화면들은 실험이 끝나기 전에 만들어졌다. 그래서 **값이 진짜인지 화면이 스스로 밝힌다.**

- fixture 파일마다 `"_mock": true`와 `"_source"`(무엇이 이걸 대체할지)를 적는다
- 헤더에 `MOCK 데이터` 배지가 뜬다. 실 API로 교체하면 `실데이터`로 바뀐다
- 실험 콘솔은 결과가 없는 실험을 **"측정 전"** 으로 남긴다 — 완료처럼 보이게 하지 않는다

## 계약 (fixture)

실 API가 붙으면 같은 모양을 그대로 쓴다.

| fixture | 대체될 것 |
|---|---|
| `homepage.json` | `GET /api/v1/personalization/homepage?userId=` (M7) |
| `experiments.json` | 실험 목록·상태(이슈/마일스톤에서 생성) |
| `exp-cache.json` | M6 측정 결과 (하네스 출력) |

`homepage.json`의 핵심 필드:

```json
{
  "source": "MODEL | FALLBACK_POPULARITY | CACHE",
  "contextStalenessMs": 420,
  "latency": { "contextMs": 11, "inferenceMs": 88, "constraintMs": 6, "totalMs": 108 },
  "rows": [ { "id": "for-you", "title": "너를 위한 추천", "strategy": "MODEL_TOP_K", "items": [ ... ] } ]
}
```

`source`와 `contextStalenessMs`는 화면에서 숨기지 않는다. **폴백으로 응답했으면 폴백이라고 보여준다.**

## 공통 자산

- `assets/pz.css` — 데이터 밀도·모노 숫자·차트 중심 디자인
- `assets/pz.js` — fixture 로더, 인라인 SVG 차트(의존성 0), 표, KPI, `api()`(실 API용 자리), 개발자 로그 드로어
