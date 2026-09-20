# apps/web — BE-commerce 웹 (Next.js)

Next.js(App Router + TypeScript) 프론트. **Spring API와 같은 출처로 붙는다.**

## 실행

백엔드가 먼저 떠 있어야 한다.

```bash
# 1) 백엔드 (리포 루트)
docker compose up -d
./gradlew bootRun           # 8080

# 2) 프론트
cd apps/web
npm install
npm run dev                 # 3000
```

- 프론트: http://localhost:3000
- 백엔드: http://localhost:8080

## API 연결 방식

| 호출 주체 | 주소 | 왜 |
|---|---|---|
| **서버 컴포넌트**(Node) | `SPRING_API`(기본 `http://localhost:8080`)로 **직접** | Node에는 상대 경로 기준이 없다 |
| **브라우저** | `/api/...` 상대 경로 | `next.config.ts`의 `rewrites`가 Spring으로 프록시 → **same-origin, CORS 불필요** |

환경변수 `SPRING_API`로 주소를 바꾼다.

```bash
SPRING_API=http://localhost:8090 npm run dev
```

## 연동 실패를 숨기지 않는다

홈은 백엔드가 죽어 있으면 그 사실을 화면에 그대로 보여준다 — 호출한 URL과 오류, 기동 방법까지.
"연동 지점이 죽으면 사용자가 아는 상태"를 화면에서 지키기 위해서다.

## 스크립트

| 명령 | 무엇 |
|---|---|
| `npm run dev` | 개발 서버(3000) |
| `npm run build` | 프로덕션 빌드 |
| `npm run start` | 빌드 결과 실행 |
| `npm run typecheck` | 타입 검사 |

## 아직 없는 것

- 디자인 토큰·레이아웃 통합(이슈 #152)
- 개인화 홈·실험 콘솔 이관(#153, #154)
- API 스텁으로 연동 실패 조기 노출(#155)
- 배포 형태(SSR vs 정적 export) 결정(#156)
