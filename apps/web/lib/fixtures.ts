import { promises as fs } from 'node:fs';
import path from 'node:path';

/**
 * 목 계약(fixture)은 정적 프론트와 **같은 파일**을 쓴다 — 계약이 두 벌이 되면 갈라진다.
 * 기본 위치는 리포의 personalization/web/fixtures, 필요하면 FIXTURES_DIR로 바꾼다.
 */
export const FIXTURES_DIR =
  process.env.FIXTURES_DIR ?? path.join(process.cwd(), '..', '..', 'personalization', 'web', 'fixtures');

export async function readFixture<T = unknown>(name: string): Promise<T> {
  const file = path.join(FIXTURES_DIR, `${name}.json`);
  const raw = await fs.readFile(file, 'utf8');
  return JSON.parse(raw) as T;
}
