import { SiteHeader } from '@/components/SiteHeader';
import { Badge, Kpi } from '@/components/ui';
import { API_MODE, SPRING_API, getCategories } from '@/lib/api';

export const dynamic = 'force-dynamic';

export default async function HomePage() {
  const res = await getCategories();

  return (
    <>
      <SiteHeader active="home" mock={API_MODE === 'stub'} />

      <div className="head">
        <h1>BE-commerce 웹</h1>
        <p>
          Next.js(App Router) 서버 컴포넌트가 데이터를 직접 가져온다. 브라우저 요청은{' '}
          <span className="mono">rewrites</span>가 프록시해 <b>같은 출처</b>를 유지한다(CORS 불필요).
        </p>
      </div>

      <div className="grid g3" style={{ marginBottom: 18 }}>
        <Kpi label="데이터 출처" value={API_MODE === 'stub' ? 'STUB' : 'REAL'} desc={API_MODE === 'stub' ? '같은 앱의 /stub 라우트(목 계약)' : 'Spring API'} kind={API_MODE === 'stub' ? 'warn' : 'good'} />
        <Kpi label="상점 카탈로그" value={res.ok ? `${res.data.length} 카테고리` : '연결 실패'} desc={`Spring ${SPRING_API}`} kind={res.ok ? 'good' : 'bad'} />
        <Kpi label="개인화 백엔드" value="미착수" desc="M7에서 실 API로 전환" />
      </div>

      {res.ok ? (
        <div className="grid g3">
          {res.data.map((c) => (
            <article className="panel" key={c.code}>
              <h3>{c.name}</h3>
              <p className="muted" style={{ fontSize: 13, margin: '6px 0 0' }}>{c.description}</p>
              <div className="mono" style={{ color: 'var(--accent-ink)', marginTop: 8 }}>
                {c.productCount.toLocaleString('ko-KR')}개
              </div>
              <div className="mono" style={{ fontSize: 11, color: 'var(--sub)', marginTop: 6 }}>{c.code}</div>
            </article>
          ))}
        </div>
      ) : (
        <section className="fail">
          <h2 style={{ fontSize: 16 }}>백엔드에 닿지 못했습니다</h2>
          <p className="muted" style={{ marginTop: 6 }}>
            이 화면은 실패를 숨기지 않는다. 연동 지점이 죽으면 사용자가 아는 상태여야 한다.
          </p>
          <p className="mono" style={{ fontSize: 12.5, margin: '10px 0 0' }}>GET {res.url}</p>
          <p style={{ margin: '6px 0 0' }}>
            <code>{res.error}</code>
          </p>
          <p className="muted" style={{ marginTop: 12, fontSize: 13 }}>
            기동: <span className="mono">docker compose up -d</span> 후 <span className="mono">./gradlew bootRun</span>
          </p>
        </section>
      )}

      <p className="muted" style={{ marginTop: 18, fontSize: 12.5 }}>
        개인화 화면은 백엔드가 아직 없어 <b>스텁 계약</b>으로 돈다. 연동 실패를 보고 싶으면{' '}
        <span className="mono">/personalization?delay=3000</span> 또는 <span className="mono">?fail=503</span> 을 붙여보라.{' '}
        <Badge kind="info">API_MODE={API_MODE}</Badge>
      </p>
    </>
  );
}
