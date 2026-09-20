import { NextResponse, type NextRequest } from 'next/server';
import { readFixture } from '@/lib/fixtures';

/**
 * 목 계약 스텁.
 *
 * 왜 두는가: 지금은 붙을 백엔드가 없다. 그런데 "프론트 연동에서만 생기는 문제"(지연·타임아웃·실패·
 * 부분 응답)는 **연동 지점을 실제로 통과해야** 드러난다. 이 라우트가 그 지점을 만들어 준다.
 *
 * 손잡이(쿼리): `?delay=2000`(지연 ms, 최대 10초) `?fail=503`(지정 상태로 실패)
 *
 * 주의: 실제 API는 `/api/*` → Spring(rewrites)이다. 스텁은 `/stub/*`에 두어 실제 경로를 가리지 않는다.
 */
export async function GET(req: NextRequest, ctx: { params: Promise<{ path: string[] }> }) {
  const { path } = await ctx.params;
  const sp = req.nextUrl.searchParams;

  const delay = Math.min(Math.max(Number(sp.get('delay') ?? 0) || 0, 0), 10_000);
  if (delay) await new Promise((r) => setTimeout(r, delay));

  const fail = sp.get('fail');
  if (fail) {
    const status = Math.min(Math.max(Number(fail) || 500, 400), 599);
    return NextResponse.json({ error: `injected failure ${status}` }, { status });
  }

  const name = path.join('/');
  const fixture =
    name === 'homepage'
      ? 'homepage'
      : name === 'experiments'
        ? 'experiments'
        : name.startsWith('experiments/')
          ? `exp-${name.slice('experiments/'.length)}`
          : name === 'categories'
            ? 'categories'
            : null;

  if (!fixture) {
    return NextResponse.json({ error: `unknown stub path: ${name}` }, { status: 404 });
  }

  try {
    const data = await readFixture<Record<string, unknown>>(fixture);
    return NextResponse.json(data, { headers: { 'x-stub': fixture } });
  } catch (e) {
    return NextResponse.json(
      { error: `fixture 없음: ${fixture}`, detail: e instanceof Error ? e.message : String(e) },
      { status: 404 },
    );
  }
}
