import { headers } from 'next/headers';
import type { Category, ExperimentSummary, ExperimentsFile, Homepage } from './contracts';

/** 실 API(Spring) 주소. 개발 기본 8080. */
export const SPRING_API = process.env.SPRING_API ?? 'http://localhost:8080';

/**
 * 데이터 출처.
 * - `stub`(기본): 같은 앱의 `/stub/*` 라우트가 목 계약을 HTTP로 서빙한다 — 연동 지점을 실제로 통과한다
 * - `real`: Spring API. 개인화 백엔드가 붙는 M7에 켠다
 */
export const API_MODE: 'stub' | 'real' = process.env.API_MODE === 'real' ? 'real' : 'stub';
export const IS_MOCK = API_MODE === 'stub';

export type Result<T> = { ok: true; data: T } | { ok: false; error: string; url: string; status?: number };

/** 연동 실패를 일부러 만들어 보기 위한 주입값. stub에서만 동작한다. */
export type Injection = { delay?: number; fail?: number };

/** 서버 컴포넌트에서 자기 자신의 절대 주소가 필요할 때(상대 경로 기준이 없다). */
async function selfOrigin(): Promise<string> {
  if (process.env.SELF_ORIGIN) return process.env.SELF_ORIGIN;
  const h = await headers();
  const host = h.get('host') ?? 'localhost:3000';
  const proto = h.get('x-forwarded-proto') ?? 'http';
  return `${proto}://${host}`;
}

/**
 * 경로는 자원 이름으로 받고, 모드에 따라 실제 경로를 고른다.
 *
 * `categories`(상점 카탈로그)는 **이미 실재하는 Spring API**라 항상 Spring을 쓴다. 개인화 자원만
 * 백엔드가 아직 없어 스텁/실 모드를 탄다.
 */
const PATHS = {
  homepage: { stub: '/homepage', real: '/api/v1/personalization/homepage' },
  experiments: { stub: '/experiments', real: '/api/v1/personalization/experiments' },
  experiment: { stub: '/experiments/', real: '/api/v1/personalization/experiments/' },
} as const;

async function fetchJson<T>(url: URL, headers?: Record<string, string>): Promise<Result<T>> {
  try {
    const res = await fetch(url, { cache: 'no-store', headers });
    if (!res.ok) return { ok: false, error: `HTTP ${res.status}`, url: url.toString(), status: res.status };
    return { ok: true, data: (await res.json()) as T };
  } catch (e) {
    // 백엔드(또는 스텁)가 안 떠 있으면 여기로 온다. 화면이 이 실패를 숨기지 않게 그대로 올린다.
    return { ok: false, error: e instanceof Error ? e.message : String(e), url: url.toString() };
  }
}

/**
 * 실 API용 토큰 — **홈은 그 사용자의 활동으로 조립되므로 본인 토큰이 있어야 한다.**
 *
 * 서버 컴포넌트가 백엔드를 부르는데 브라우저 세션이 없으므로, 데모 계정으로 한 번 로그인해 토큰을
 * 얻는다(운영이라면 세션·쿠키·서비스 계정에서 온다). 스텁은 인증이 없어 이 경로를 타지 않는다.
 *
 * **실패를 숨기지 않는다**: 토큰을 못 얻으면 그대로 실패로 돌려주고, 화면이 그 사실을 보여준다.
 */
async function demoToken(): Promise<Result<string>> {
  const url = new URL(`${SPRING_API}/api/v1/auth/login`);
  const username = process.env.DEMO_USER ?? '1';
  const password = process.env.DEMO_PASSWORD ?? 'user-local-only';
  try {
    const res = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password }),
      cache: 'no-store',
    });
    if (!res.ok) return { ok: false, error: `로그인 HTTP ${res.status}`, url: url.toString(), status: res.status };
    const token = ((await res.json()) as { token?: string }).token;
    if (!token) return { ok: false, error: '로그인 응답에 토큰이 없다', url: url.toString() };
    return { ok: true, data: token };
  } catch (e) {
    return { ok: false, error: e instanceof Error ? e.message : String(e), url: url.toString() };
  }
}

async function getJson<T>(resource: keyof typeof PATHS, suffix = '', inj: Injection = {}): Promise<Result<T>> {
  const base = API_MODE === 'stub' ? `${await selfOrigin()}/stub` : SPRING_API;
  const url = new URL(base + PATHS[resource][API_MODE] + suffix);

  // 주입은 stub에서만 의미가 있다 — 실 백엔드에는 이런 손잡이가 없다.
  if (API_MODE === 'stub') {
    if (inj.delay) url.searchParams.set('delay', String(inj.delay));
    if (inj.fail) url.searchParams.set('fail', String(inj.fail));
  }

  return fetchJson<T>(url);
}

/** 상점 카탈로그 — 실재하는 Spring API. */
export const getCategories = () => fetchJson<Category[]>(new URL(`${SPRING_API}/api/v1/categories`));

/**
 * 개인화 홈 — 실 API는 **인증이 필요하다**. 그래서 스텁과 경로가 다르다(토큰을 얻어 실어 보낸다).
 */
export async function getHomepage(inj?: Injection): Promise<Result<Homepage>> {
  if (API_MODE === 'stub') return getJson<Homepage>('homepage', '', inj);
  const token = await demoToken();
  if (!token.ok) return token;
  return fetchJson<Homepage>(new URL(`${SPRING_API}${PATHS.homepage.real}`),
    { Authorization: `Bearer ${token.data}` });
}

export const getExperiments = (inj?: Injection) => getJson<ExperimentsFile>('experiments', '', inj);
export const getExperiment = (id: string, inj?: Injection) =>
  getJson<Record<string, unknown>>('experiment', id, inj);

export function experimentPath(id: string) {
  return `/personalization/experiments/${id}`;
}

export type { ExperimentSummary };
