import { SiteHeader } from '@/components/SiteHeader';
import { Badge } from '@/components/ui';
import { API_MODE, experimentPath, getExperiments } from '@/lib/api';
import type { ExperimentStatus } from '@/lib/contracts';

export const dynamic = 'force-dynamic';

const STATUS_LABEL: Record<ExperimentStatus, { label: string; kind: 'ok' | 'warn' | 'bad' | 'info' | '' }> = {
  idea: { label: '설계', kind: '' },
  todo: { label: '측정 전', kind: '' },
  running: { label: '측정 중', kind: 'warn' },
  done: { label: '측정 완료', kind: 'ok' },
};

export default async function ConsolePage({ searchParams }: { searchParams: Promise<{ delay?: string; fail?: string }> }) {
  const sp = await searchParams;
  const inj = { delay: sp.delay ? Number(sp.delay) : undefined, fail: sp.fail ? Number(sp.fail) : undefined };
  const res = await getExperiments(inj);
  const injected = Boolean(inj.delay || inj.fail);

  return (
    <>
      <SiteHeader active="console" mock={API_MODE === 'stub'} />

      <div className="head">
        <h1>실험 콘솔</h1>
        <p>
          이 프로젝트의 본체는 모델이 아니라 <b>측정</b>이다. 각 실험은 “무엇을 더 보장하면 무엇을 얼마나 지불하는가”를
          숫자로 남긴다.{' '}
          {injected ? (
            <Badge kind="warn">
              주입됨 — {inj.delay ? `delay=${inj.delay}ms` : ''} {inj.fail ? `fail=${inj.fail}` : ''}
            </Badge>
          ) : null}
        </p>
      </div>

      {!res.ok ? (
        <section className="fail">
          <h2 style={{ fontSize: 16 }}>실험 목록을 받지 못했습니다</h2>
          <p className="mono" style={{ fontSize: 12.5, margin: '10px 0 0' }}>GET {res.url}</p>
          <p style={{ margin: '6px 0 0' }}>
            <code>{res.error}</code>
          </p>
        </section>
      ) : (
        <>
          <div className="grid g3">
            {res.data.experiments.map((e) => {
              const st = STATUS_LABEL[e.status];
              const done = e.status === 'done' && e.href;
              return (
                <article className="exp" key={e.id}>
                  <div style={{ display: 'flex', justifyContent: 'space-between', gap: 8 }}>
                    <h3>{e.title}</h3>
                    <Badge kind={st.kind}>{st.label}</Badge>
                  </div>
                  <p className="q">{e.question}</p>
                  <div className="mono" style={{ fontSize: 12, color: 'var(--sub)' }}>
                    {e.milestone} · 이슈 #{e.issue}
                  </div>
                  <div className="foot">
                    {done ? (
                      <a href={experimentPath(e.id)}>결과 보기 →</a>
                    ) : (
                      <span>화면 준비 중 — 실험 이슈에서 진행</span>
                    )}
                  </div>
                  {e.summary ? (
                    <div>
                      <Badge kind="ok">{e.summary}</Badge>
                    </div>
                  ) : null}
                </article>
              );
            })}
          </div>
          <p className="muted" style={{ marginTop: 22, fontSize: 12.5 }}>
            측정 전인 실험은 <b>“측정 전”</b>으로 남긴다. 결과가 나오지 않은 것을 완료처럼 보이게 하지 않는다.
          </p>
        </>
      )}
    </>
  );
}
