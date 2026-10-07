import { defineConfig } from 'vitest/config';

/**
 * 순수 로직(`lib/shortsFeed.ts` 등)만 돈다 — DOM이 필요 없어 jsdom 없이 node 환경으로 충분하다.
 * 화면(`ShortsFeed` 컴포넌트)의 실제 동작 확인은 README대로 브라우저에서 `/shorts`를 직접 본다.
 */
export default defineConfig({
  test: {
    environment: 'node',
    include: ['**/*.test.ts'],
  },
});
