/** 서버 컴포넌트에서 Spring API를 직접 부른다. 브라우저 요청은 next.config.ts의 rewrite가 프록시한다. */

export const SPRING_API = process.env.SPRING_API ?? 'http://localhost:8080';

export type Category = {
  code: string;
  name: string;
  description: string | null;
  sortOrder: number;
  productCount: number;
};

export type Result<T> = { ok: true; data: T } | { ok: false; error: string; url: string; status?: number };

async function getJson<T>(path: string): Promise<Result<T>> {
  const url = `${SPRING_API}${path}`;
  try {
    const res = await fetch(url, { cache: 'no-store' });
    if (!res.ok) return { ok: false, error: `HTTP ${res.status}`, url, status: res.status };
    return { ok: true, data: (await res.json()) as T };
  } catch (e) {
    // 백엔드가 안 떠 있으면 여기로 온다. 화면이 이 실패를 숨기지 않게 그대로 올린다.
    return { ok: false, error: e instanceof Error ? e.message : String(e), url };
  }
}

export function getCategories() {
  return getJson<Category[]>('/api/v1/categories');
}
