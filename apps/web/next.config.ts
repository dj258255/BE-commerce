import type { NextConfig } from 'next';

/**
 * Spring Boot API 주소. 개발 중에는 기본 8080.
 *
 * 브라우저에서 부르는 요청은 rewrite로 프록시해 **same-origin**을 유지한다 — CORS 설정이 필요 없고,
 * 쿠키/세션 의미도 그대로다. 서버 컴포넌트는 Node에서 직접 부르므로 아래 절대 주소를 쓴다.
 */
const SPRING_API = process.env.SPRING_API ?? 'http://localhost:8080';

const nextConfig: NextConfig = {
  async rewrites() {
    return [{ source: '/api/:path*', destination: `${SPRING_API}/api/:path*` }];
  },
};

export default nextConfig;
