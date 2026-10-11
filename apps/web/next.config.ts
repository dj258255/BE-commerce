import type { NextConfig } from 'next';

/**
 * Spring Boot API 주소. 개발 중에는 기본 8080.
 *
 * 브라우저에서 부르는 요청은 rewrite로 프록시해 **same-origin**을 유지한다 — CORS 설정이 필요 없고,
 * 쿠키/세션 의미도 그대로다. 서버 컴포넌트는 Node에서 직접 부르므로 아래 절대 주소를 쓴다.
 *
 * `/uploads/*`(상품 이미지)도 같이 프록시한다. 이미지는 Spring이 리포 밖 디렉터리에서 정적으로 서빙한다.
 *
 * `/product.html`·`/assets/*`도 같은 이유로 프록시한다(R26) — 숏폼 피드의 연결 상품을 누르면
 * Spring이 서빙하는 기존 상품 상세 페이지(`commerce/src/main/resources/static/product.html`)로
 * 가야 한다. `SPRING_API`(컨테이너 내부 주소, 예: `http://commerce:8080`)는 브라우저가 직접 못
 * 열 수 있어 절대주소로 링크하면 깨진다 — same-origin 상대경로로 링크하고 여기서 프록시해야
 * 어떤 환경에서도 동작한다. `product.html`이 상대경로로 불러오는 `assets/store.css`·`store.js`도
 * 같은 origin에서 풀려 `/assets/*`가 함께 필요하다.
 *
 * **숏폼 재생(R26) HLS 재생목록·세그먼트·썸네일**(`GET /api/v1/shorts/{id}/media/**`)도 이
 * `/api/:path*` 규칙 하나로 같이 풀린다 — 피드가 내려주는 `masterPlaylistUrl`·`thumbnailUrl`이
 * 전부 `/api/v1/...`로 시작하는 이 사이트 기준 상대 경로라서다. `Range` 요청도 Next.js
 * rewrite가 원시 HTTP로 재전송하므로 206/`Content-Range`가 그대로 온다. Route Handler로
 * 다시 구현하지 않은 이유·검증(web 출처 curl)은 ADR-081 "현행화(R26 재생)" 절 참고.
 */
const SPRING_API = process.env.SPRING_API ?? 'http://localhost:8080';

const nextConfig: NextConfig = {
  async rewrites() {
    return [
      { source: '/api/:path*', destination: `${SPRING_API}/api/:path*` },
      { source: '/uploads/:path*', destination: `${SPRING_API}/uploads/:path*` },
      { source: '/product.html', destination: `${SPRING_API}/product.html` },
      { source: '/assets/:path*', destination: `${SPRING_API}/assets/:path*` },
    ];
  },
};

export default nextConfig;
