export type BadgeKind = 'ok' | 'warn' | 'bad' | 'info' | '';

export function Badge({ kind = '', children }: { kind?: BadgeKind; children: React.ReactNode }) {
  return <span className={`badge ${kind ? `b-${kind}` : ''}`}>{children}</span>;
}

/** 값의 출처를 화면이 스스로 밝힌다 — 목이면 목이라고. */
export function MockBadge({ mock }: { mock: boolean }) {
  return <span className={`mock${mock ? '' : ' off'}`}>{mock ? 'MOCK 데이터' : '실데이터'}</span>;
}

export function Kpi({
  label,
  value,
  desc,
  kind = '',
}: {
  label: string;
  value: React.ReactNode;
  desc?: React.ReactNode;
  kind?: 'good' | 'warn' | 'bad' | '';
}) {
  return (
    <div className={`kpi ${kind}`}>
      <div className="k">{label}</div>
      <div className="v">{value}</div>
      {desc ? <div className="d">{desc}</div> : null}
    </div>
  );
}
