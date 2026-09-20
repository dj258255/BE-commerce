import { SiteHeader } from '@/components/SiteHeader';
import { Badge, Kpi } from '@/components/ui';
import { API_MODE, getHomepage } from '@/lib/api';
import { gradient, won } from '@/lib/ui';

export const dynamic = 'force-dynamic';

type SearchParams = Promise<{ delay?: string; fail?: string }>;

export default async function PersonalizationHome({ searchParams }: { searchParams: SearchParams }) {
  const sp = await searchParams;
  const inj = {
    delay: sp.delay ? Number(sp.delay) : undefined,
    fail: sp.fail ? Number(sp.fail) : undefined,
  };
  const res = await getHomepage(inj);
  const injected = Boolean(inj.delay || inj.fail);

  return (
    <>
      <SiteHeader active="personalization" mock={API_MODE === 'stub'} />

      <div className="head">
        <h1>개인화 홈</h1>
        <p>
          모델이 홈을 구성한 결과다. 이 화면은 결과만 보여주지 않고 <b>어디서 왔고 얼마를 썼는지</b>를 함께 보여준다.
          {injected ? (
            <>
              {' '}
              <Badge kind="warn">
                주입됨 — {inj.delay ? `delay=${inj.delay}ms` : ''} {inj.fail ? `fail=${inj.fail}` : ''}
              </Badge>
            </>
          ) : null}
        </p>
      </div>

      {!res.ok ? (
        <section className="fail">
          <h2 style={{ fontSize: 16 }}>개인화 데이터를 받지 못했습니다</h2>
          <p className="muted" style={{ marginTop: 6 }}>
            이 화면은 실패를 숨기지 않는다. 연동 지점이 죽으면 사용자가 아는 상태여야 한다.
          </p>
          <p className="mono" style={{ fontSize: 12.5, margin: '10px 0 0' }}>GET {res.url}</p>
          <p style={{ margin: '6px 0 0' }}>
            <code>{res.error}</code>
          </p>
          <p className="muted" style={{ marginTop: 12, fontSize: 13 }}>
            주입을 빼고 다시 열거나, 스텁이 떠 있는지 확인하세요. (<span className="mono">API_MODE={API_MODE}</span>)
          </p>
        </section>
      ) : (
        <>
          <div className="grid g4" style={{ marginBottom: 20 }}>
            <Kpi
              label="구성 출처"
              value={res.data.source}
              desc={res.data.fallbackReason ?? '모델이 구성함'}
              kind={res.data.source === 'MODEL' ? '' : 'warn'}
            />
            <Kpi label="컨텍스트 신선도" value={`${res.data.contextStalenessMs}ms`} desc="가장 최근 반영된 이벤트 기준" />
            <Kpi
              label="총 지연"
              value={`${res.data.latency.totalMs}ms`}
              desc={`context ${res.data.latency.contextMs} · inference ${res.data.latency.inferenceMs} · constraint ${res.data.latency.constraintMs}`}
            />
            <Kpi label="행 수" value={res.data.rows.length} desc="각 행은 서로 다른 전략으로 만든다" />
          </div>

          {res.data.rows.map((row) => (
            <section className="panel" key={row.id} style={{ marginBottom: 14 }}>
              <div style={{ display: 'flex', alignItems: 'baseline', justifyContent: 'space-between', marginBottom: 12 }}>
                <h2>{row.title}</h2>
                <span className="mono" style={{ fontSize: 12, color: 'var(--sub)' }}>{row.strategy}</span>
              </div>
              <div className="grid g4">
                {row.items.map((it) => (
                  <article className="panel" key={it.itemId} style={{ padding: 0, overflow: 'hidden' }}>
                    <div style={{ aspectRatio: '3 / 4', background: gradient(it.itemId), display: 'grid', placeItems: 'center', color: '#fff', fontWeight: 800, fontSize: 22 }}>
                      {it.name.trim().charAt(0)}
                    </div>
                    <div style={{ padding: '9px 10px' }}>
                      <div style={{ fontSize: 12.5, fontWeight: 650 }}>{it.name}</div>
                      <div className="mono" style={{ fontSize: 10.5, color: 'var(--sub)', marginTop: 5 }}>{it.reason}</div>
                      <div className="mono" style={{ fontSize: 11, color: 'var(--accent-ink)', marginTop: 4 }}>
                        {won(it.price)} · score {it.score.toFixed(2)}
                      </div>
                    </div>
                  </article>
                ))}
              </div>
            </section>
          ))}
        </>
      )}
    </>
  );
}
