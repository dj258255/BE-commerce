# apps/web — BE-commerce 웹 (Next.js)

Next.js(App Router + TypeScript) 프론트. 상점과 개인화 콘솔이 **같은 토큰과 컴포넌트**를 쓴다.

## 실행

```bash
cd apps/web
npm install
npm run dev                 # 3000
```

- 개요: http://localhost:3000
- 상점 카탈로그는 Spring API가 있어야 뜬다: `docker compose up -d` 후 리포 루트에서 `./gradlew bootRun`
- 개인화 화면은 백엔드가 아직 없어 **스텁**으로 돈다(아래 참고)

## 데이터 출처 (API_MODE)

| 모드 | 개인화 자원 | 상점 카탈로그 |
|---|---|---|
| `stub`(기본) | 같은 앱의 `/stub/*` 라우트가 **목 계약**을 HTTP로 서빙 | Spring `GET /api/v1/categories` |
| `real` | Spring `GET /api/v1/personalization/*` (M7에서 구현) | 〃 |

```bash
API_MODE=real SPRING_API=http://localhost:8080 npm run dev
```

`categories`는 **이미 실재하는 Spring API**라 모드와 무관하게 항상 Spring을 쓴다.

## 연동 실패를 일부러 만들어 보기

백엔드가 없어도 **연동 지점의 실패**를 재현할 수 있다. 스텁에 손잡이를 달아 두었다.

```bash
curl 'localhost:3000/stub/homepage?delay=800'   # 800ms 지연
curl 'localhost:3000/stub/homepage?fail=503'    # 503 실패
```

화면에서도 같은 값을 넘길 수 있다.

```
/personalization?delay=3000     # 느린 백엔드
/personalization?fail=503       # 죽은 백엔드
```

이 손잡이가 실험 화면(로딩·타임아웃·폴백)과 이어진다. **실 API에는 이런 손잡이가 없다** — 그래서 주입은 stub에서만 동작한다.

## 구조

| 위치 | 무엇 |
|---|---|
| `app/globals.css` | 디자인 토큰 + 컴포넌트 클래스 |
| `components/SiteHeader.tsx` | 헤더·내비·MOCK 배지 |
| `components/ui.tsx` | `Badge`·`Kpi`·`MockBadge` |
| `components/DataTable.tsx` | 모노 숫자 표 |
| `components/LineChart.tsx` | 인라인 SVG 라인 차트(의존성 0) |
| `components/Charts.tsx` | 가로 막대(`Bars`)·요청 타임라인(`Timeline`) |
| `lib/contracts.ts` | 계약 타입 — fixture와 실 API가 같은 모양 |
| `lib/api.ts` | 데이터 출처 결정(`API_MODE`)·실패를 숨기지 않는 결과 타입 |
| `lib/fixtures.ts` | 목 계약 파일 로더(정적 프론트와 **같은 파일**을 쓴다) |
| `app/stub/[...path]/route.ts` | 목 계약 스텁(지연·실패 주입) |

## 왜 스텁을 두는가

붙을 백엔드가 없으면 "프론트 연동에서만 생기는 문제"(지연·타임아웃·부분 실패)가 **드러나지 않는다.**
스텁은 그 지점을 실제로 통과시켜 문제를 앞당겨 노출한다. 이슈 #155.

## 스크립트

| 명령 | 무엇 |
|---|---|
| `npm run dev` | 개발 서버(3000). `scripts/dev.mjs`가 `NODE_ENV=development`를 고정한다 |
| `npm run build` | 프로덕션 빌드 |
| `npm run start` | 빌드 결과 실행 |
| `npm run typecheck` | 타입 검사 |

## 배포 형태

**SSR(Node 유지)을 기본으로 한다.** 이 화면들은 신선도·지연을 재는 것이 목적이라 요청 시 계산이 자연스럽다.

- 프론트와 백엔드를 **독립 배포**해야 하거나 Node 런타임을 없애야 하면 `output: 'export'`(정적)로 바꾼다
- 대신 서버 컴포넌트의 데이터 페칭을 잃는다. 지금은 바꾸지 않는다 — 이유는 `docs/adr/ADR-032-frontend-nextjs-adoption.md`

데모 실행은 단계가 늘었다(백엔드 + 프론트). 실패 지점도 늘었다: 백엔드만 죽으면 카탈로그가, 프론트만 죽으면 화면 전체가 안 뜬다.

## 아직 없는 것

- 개인화 홈·실험 콘솔 이관(#153, #154)
- 실 API 전환(M7)
