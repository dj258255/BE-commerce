import { ShortsFeed } from '@/components/ShortsFeed';
import { SiteHeader } from '@/components/SiteHeader';
import { getShortsFeed } from '@/lib/api';

export const dynamic = 'force-dynamic';

/**
 * 숏폼 피드 페이지(R26) — `/shorts`. 서버 컴포넌트가 SPRING_API로 첫 쪽을 가져오고(다른 서버
 * 페이지와 같은 방식), 세로 스와이프·재생 상태·프리페치·더 불러오기는 클라이언트 컴포넌트
 * ({@link ShortsFeed})가 맡는다. 로그인 없이 보이는 공개 엔드포인트라 토큰이 필요 없다.
 */
export default async function ShortsPage() {
  const first = await getShortsFeed({ size: 10 });

  return (
    <>
      <SiteHeader active="shorts" mock={false} />

      <div className="head">
        <h1>숏폼</h1>
        <p>
          세로로 스와이프해 다음 영상으로 넘어간다. 변환이 끝난(<span className="mono">READY</span>) 영상만
          보이고, 영상 아래 연결 상품을 누르면 상품 상세로 간다.
        </p>
      </div>

      <ShortsFeed initial={first} />
    </>
  );
}
