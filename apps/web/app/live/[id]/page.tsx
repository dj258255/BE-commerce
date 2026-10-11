import { LiveViewer } from '@/components/LiveViewer';

export const dynamic = 'force-dynamic';

/**
 * 라이브 방송 시청 페이지(R8·R9) — `/live/{id}`. 로그인 없이 보이는 공개 화면이다(R5와 같은
 * 원칙). 재생·고정 카드 동기화는 전부 클라이언트 컴포넌트({@link LiveViewer})가 맡는다 — 서버
 * 컴포넌트는 id 파싱만 한다(스트림 키 등 민감한 값을 다루지 않는다).
 */
export default async function LivePage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  const broadcastId = Number(id);

  if (!Number.isInteger(broadcastId) || broadcastId <= 0) {
    return <div className="shorts-empty">올바르지 않은 방송입니다.</div>;
  }

  return <LiveViewer broadcastId={broadcastId} />;
}
