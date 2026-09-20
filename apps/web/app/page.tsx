import { getCategories, SPRING_API } from '@/lib/api';

// 홈은 매 요청 서버에서 데이터를 가져온다(캐시하지 않는다) — 신선한 카탈로그를 보여야 하기 때문.
export const dynamic = 'force-dynamic';

export default async function HomePage() {
  const res = await getCategories();

  return (
    <>
      <div className="head">
        <h1>BE-commerce 웹</h1>
        <p>
          Next.js(App Router) 서버 컴포넌트가 Spring API를 직접 호출한다. 브라우저에서 부르는 요청은
          <span className="mono"> rewrites </span>가 프록시해 <b>같은 출처</b>를 유지한다(CORS 불필요).
        </p>
      </div>

      <p style={{ marginTop: 18 }}>
        {res.ok ? (
          <span className="badge b-ok">Spring API 연결됨 · 카테고리 {res.data.length}개</span>
        ) : (
          <span className="badge b-bad">Spring API 연결 실패</span>
        )}
      </p>

      {res.ok ? (
        <div className="grid" style={{ marginTop: 16 }}>
          {res.data.map((c) => (
            <article className="card" key={c.code}>
              <h3>{c.name}</h3>
              <p className="muted" style={{ fontSize: 13, margin: '6px 0 0' }}>{c.description}</p>
              <div className="cnt">{c.productCount.toLocaleString('ko-KR')}개</div>
              <div className="mono" style={{ fontSize: 11, color: 'var(--sub)', marginTop: 6 }}>{c.code}</div>
            </article>
          ))}
        </div>
      ) : (
        <section className="fail" style={{ marginTop: 16 }}>
          <h2 style={{ fontSize: 16 }}>백엔드에 닿지 못했습니다</h2>
          <p className="muted" style={{ marginTop: 6 }}>
            이 화면은 실패를 숨기지 않는다. 연동 지점이 죽으면 사용자가 아는 상태여야 한다.
          </p>
          <p className="mono" style={{ fontSize: 12.5, margin: '10px 0 0' }}>
            GET {res.url}
          </p>
          <p style={{ margin: '6px 0 0' }}>
            <code>{res.error}</code>
          </p>
          <p className="muted" style={{ marginTop: 12, fontSize: 13 }}>
            기동: <span className="mono">docker compose up -d</span> 후{' '}
            <span className="mono">./gradlew bootRun</span>
          </p>
        </section>
      )}

      <p className="muted" style={{ marginTop: 18, fontSize: 12.5 }}>
        서버 렌더 확인: 이 페이지 HTML에 위 데이터가 이미 들어 있다(브라우저 소스 보기). API 주소:{' '}
        <span className="mono">{SPRING_API}</span>
      </p>
    </>
  );
}
