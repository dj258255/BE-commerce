import { notFound } from 'next/navigation';
import { SiteHeader } from '@/components/SiteHeader';
import { EXPERIMENT_SCREENS } from '@/components/experiments';
import { Badge } from '@/components/ui';
import { API_MODE, getExperiment } from '@/lib/api';

export const dynamic = 'force-dynamic';

type Props = {
  params: Promise<{ id: string }>;
  searchParams: Promise<{ delay?: string; fail?: string }>;
};

export default async function ExperimentPage({ params, searchParams }: Props) {
  const { id } = await params;
  const sp = await searchParams;
  const screen = EXPERIMENT_SCREENS[id];
  if (!screen) notFound();

  const inj = { delay: sp.delay ? Number(sp.delay) : undefined, fail: sp.fail ? Number(sp.fail) : undefined };
  const res = await getExperiment(id, inj);

  return (
    <>
      <SiteHeader active="console" mock={API_MODE === 'stub'} />

      <div className="head">
        <p style={{ marginBottom: 6, fontSize: 12.5 }}>
          <a href="/personalization/console" style={{ color: 'var(--accent-ink)' }}>
            ← 실험 콘솔
          </a>
        </p>
        <h1>{screen.title}</h1>
        <p>
          {screen.question}{' '}
          {inj.delay || inj.fail ? (
            <Badge kind="warn">
              주입됨 — {inj.delay ? `delay=${inj.delay}ms` : ''} {inj.fail ? `fail=${inj.fail}` : ''}
            </Badge>
          ) : null}
        </p>
      </div>

      {!res.ok ? (
        <section className="fail">
          <h2 style={{ fontSize: 16 }}>실험 데이터를 받지 못했습니다</h2>
          <p className="mono" style={{ fontSize: 12.5, margin: '10px 0 0' }}>GET {res.url}</p>
          <p style={{ margin: '6px 0 0' }}>
            <code>{res.error}</code>
          </p>
        </section>
      ) : (
        screen.render(res.data as never)
      )}
    </>
  );
}
